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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks that Liquibase ran on startup and created the schema described in db/changelog.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LiquibaseMigrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("delete from users");
    }

    @Test
    void changesetIsRecordedInChangelogTable() {
        List<String> applied = jdbc.queryForList(
                "select id from databasechangelog order by orderexecuted", String.class);

        assertThat(applied).containsExactly("001-create-users-table");
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
                        "email_verified_at timestamp with time zone YES",
                        "password_hash character varying NO",
                        "password_changed_at timestamp with time zone YES",
                        "status character varying NO",
                        "created_at timestamp with time zone NO");
    }

    @Test
    void newUserGetsDefaults() {
        insertUser("anton@example.com");

        Map<String, Object> user = jdbc.queryForMap("select * from users where email = 'anton@example.com'");

        assertThat(user.get("id")).isNotNull();
        assertThat(user.get("status")).isEqualTo("PENDING");
        assertThat(user.get("created_at")).isNotNull();
        assertThat(user.get("email_verified_at")).isNull();
        assertThat(user.get("password_changed_at")).isNull();
    }

    @Test
    void emailMustBeUnique() {
        insertUser("anton@example.com");

        assertThatThrownBy(() -> insertUser("anton@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusMustBeOneOfAllowedValues() {
        for (String status : List.of("ACTIVE", "PENDING", "BLOCKED", "DELETED")) {
            jdbc.update("insert into users (email, password_hash, status) values (?, 'hash', ?)",
                    status.toLowerCase() + "@example.com", status);
        }

        assertThatThrownBy(() -> jdbc.update(
                "insert into users (email, password_hash, status) values ('x@example.com', 'hash', 'UNKNOWN')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_status");
    }

    @Test
    void emailAndPasswordHashAreRequired() {
        assertThatThrownBy(() -> jdbc.update("insert into users (password_hash) values ('hash')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into users (email) values ('anton@example.com')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertUser(String email) {
        jdbc.update("insert into users (email, password_hash) values (?, 'hash')", email);
    }

}
