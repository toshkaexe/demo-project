package demo.lavka;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks that Liquibase ran on startup and created the schema described in db/changelog.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LiquibaseMigrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        // orders first: they reference users
        jdbc.update("delete from orders");
        jdbc.update("delete from users");
        jdbc.update("delete from products");
    }

    @Test
    void changesetIsRecordedInChangelogTable() {
        List<String> applied = jdbc.queryForList(
                "select id from databasechangelog order by orderexecuted", String.class);

        assertThat(applied).containsExactly(
                "001-create-users-table", "002-create-products-table", "003-create-orders-table",
                "004-create-order-items-table", "004-create-order-status-history-table",
                "005-users-role-check");
    }

    @Test
    void usersTableHasExpectedColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, data_type, is_nullable
                from information_schema.columns
                where table_name = 'users'
                order by ordinal_position
                """);

        assertThat(columns).extracting(c -> c.get("column_name") + " " + c.get("data_type") + " " + c.get("is_nullable"))
                .containsExactly(
                        "id bigint NO",
                        "email character varying NO",
                        "password_hash character varying NO",
                        "role character varying NO",
                        "created_at timestamp with time zone NO",
                        "updated_at timestamp with time zone NO");
    }

    @Test
    void newUserGetsDefaults() {
        insertUser("anton@example.com");

        Map<String, Object> user = jdbc.queryForMap("select * from users where email = 'anton@example.com'");

        assertThat(user.get("id")).isNotNull();
        assertThat(user.get("created_at")).isNotNull();
        assertThat(user.get("updated_at")).isNotNull();
        assertThat(user.get("role")).isEqualTo("CUSTOMER");
    }

    @Test
    void emailMustBeUnique() {
        insertUser("anton@example.com");

        assertThatThrownBy(() -> insertUser("anton@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailAndPasswordHashAreRequired() {
        assertThatThrownBy(() -> jdbc.update("insert into users (password_hash) values ('hash')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into users (email) values ('a@example.com')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void productsTableHasExpectedColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, data_type, is_nullable, numeric_precision, numeric_scale
                from information_schema.columns
                where table_name = 'products'
                order by ordinal_position
                """);

        assertThat(columns).extracting(c -> c.get("column_name") + " " + c.get("data_type") + " " + c.get("is_nullable"))
                .containsExactly(
                        "id integer NO",
                        "name character varying NO",
                        "quantity integer NO",
                        "price numeric NO");
        assertThat(columns.get(3)).containsEntry("numeric_precision", 10).containsEntry("numeric_scale", 2);
    }

    @Test
    void productQuantityDefaultsToZeroAndPriceKeepsCents() {
        jdbc.update("insert into products (name, price) values ('Milk', 89.99)");

        Map<String, Object> product = jdbc.queryForMap("select * from products where name = 'Milk'");

        assertThat(product.get("id")).isNotNull();
        assertThat(product.get("quantity")).isEqualTo(0);
        assertThat(product.get("price")).hasToString("89.99");
    }

    @Test
    void productQuantityAndPriceCannotBeNegative() {
        assertThatThrownBy(() -> jdbc.update("insert into products (name, quantity, price) values ('Milk', -1, 10)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into products (name, quantity, price) values ('Milk', 1, -10)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ordersTableHasExpectedColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, data_type, is_nullable
                from information_schema.columns
                where table_name = 'orders'
                order by ordinal_position
                """);

        assertThat(columns).extracting(c -> c.get("column_name") + " " + c.get("data_type") + " " + c.get("is_nullable"))
                .containsExactly(
                        "id bigint NO",
                        "user_id bigint NO",
                        "status character varying NO",
                        "created_at timestamp with time zone NO",
                        "completed_at timestamp with time zone YES");
    }

    @Test
    void newOrderGetsDefaults() {
        long userId = insertUser("anton@example.com");
        jdbc.update("insert into orders (user_id) values (?)", userId);

        Map<String, Object> order = jdbc.queryForMap("select * from orders where user_id = ?", userId);

        assertThat(order.get("id")).isNotNull();
        assertThat(order.get("status")).isEqualTo("CREATED");
        assertThat(order.get("created_at")).isNotNull();
        assertThat(order.get("completed_at")).isNull();
    }

    @Test
    void orderMustBelongToExistingUser() {
        assertThatThrownBy(() -> jdbc.update("insert into orders (user_id) values (-1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userWithOrdersCannotBeDeleted() {
        long userId = insertUser("anton@example.com");
        jdbc.update("insert into orders (user_id) values (?)", userId);

        assertThatThrownBy(() -> jdbc.update("delete from users where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderStatusMustBeKnown() {
        long userId = insertUser("anton@example.com");

        assertThatThrownBy(() -> jdbc.update("insert into orders (user_id, status) values (?, 'LOST')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completedAtIsSetOnlyForCompletedOrders() {
        long userId = insertUser("anton@example.com");

        jdbc.update("insert into orders (user_id, status, completed_at) values (?, 'COMPLETED', now())", userId);
        assertThatThrownBy(() -> jdbc.update("insert into orders (user_id, status) values (?, 'COMPLETED')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into orders (user_id, completed_at) values (?, now())", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderItemsTableHasExpectedColumns() {
        assertThat(columnsOf("order_items")).containsExactly(
                "id bigint NO",
                "order_id bigint NO",
                "product_id integer NO",
                "quantity integer NO",
                "price numeric NO");
    }

    @Test
    void orderItemQuantityIsPositiveAndProductIsUniqueInOrder() {
        long orderId = insertOrder(insertUser("anton@example.com"));
        long productId = insertProduct();

        assertThatThrownBy(() -> insertItem(orderId, productId, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertItem(orderId, productId, 1);
        assertThatThrownBy(() -> insertItem(orderId, productId, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderedProductCannotBeDeletedButOrderTakesItsItemsAlong() {
        long orderId = insertOrder(insertUser("anton@example.com"));
        long productId = insertProduct();
        insertItem(orderId, productId, 1);

        assertThatThrownBy(() -> jdbc.update("delete from products where id = ?", productId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("delete from orders where id = ?", orderId);
        assertThat(jdbc.queryForObject("select count(*) from order_items", Integer.class)).isZero();
    }

    @Test
    void orderStatusHistoryTableHasExpectedColumns() {
        assertThat(columnsOf("order_status_history")).containsExactly(
                "id bigint NO",
                "order_id bigint NO",
                "from_status character varying YES",
                "to_status character varying NO",
                "changed_at timestamp with time zone NO",
                "changed_by bigint YES");
    }

    @Test
    void statusHistoryAcceptsOnlyKnownAndActualChanges() {
        long orderId = insertOrder(insertUser("anton@example.com"));

        jdbc.update("insert into order_status_history (order_id, to_status) values (?, 'CREATED')", orderId);
        assertThatThrownBy(() -> jdbc.update(
                "insert into order_status_history (order_id, from_status, to_status) values (?, 'CREATED', 'LOST')", orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into order_status_history (order_id, from_status, to_status) values (?, 'READY', 'READY')", orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userRoleMustBeKnown() {
        long userId = insertUser("anton@example.com");

        jdbc.update("update users set role = 'WAREHOUSE' where id = ?", userId);
        assertThatThrownBy(() -> jdbc.update("update users set role = 'ADMIN' where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList("""
                        select column_name, data_type, is_nullable
                        from information_schema.columns
                        where table_name = ?
                        order by ordinal_position
                        """, table).stream()
                .map(c -> c.get("column_name") + " " + c.get("data_type") + " " + c.get("is_nullable"))
                .toList();
    }

    private long insertOrder(long userId) {
        return jdbc.queryForObject("insert into orders (user_id) values (?) returning id", Long.class, userId);
    }

    private long insertProduct() {
        return jdbc.queryForObject("insert into products (name, price) values ('Milk', 10) returning id", Long.class);
    }

    private void insertItem(long orderId, long productId, int quantity) {
        jdbc.update("insert into order_items (order_id, product_id, quantity, price) values (?, ?, ?, 10)",
                orderId, productId, quantity);
    }

    private long insertUser(String email) {
        jdbc.update("insert into users (email, password_hash) values (?, 'hash')", email);
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

}
