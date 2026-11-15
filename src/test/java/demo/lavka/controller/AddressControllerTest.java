package demo.lavka.controller;

import com.jayway.jsonpath.JsonPath;
import demo.lavka.TestcontainersConfiguration;
import demo.lavka.repository.UserAddressRepository;
import demo.lavka.repository.UserRepository;
import demo.lavka.service.AddressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AddressControllerTest {

    private static final String HOME = """
            {"postal_code": " 101000 ", "country": "Россия", "city": "Москва", "street": "Тверская",
             "house": "12к2", "apartment": "45", "recipient_name": "Антон Иванов",
             "courier_note": "подъезд 3, домофон 45"}
            """;
    // a private house: no apartment, no note
    private static final String DACHA = """
            {"postal_code": "141500", "country": "Россия", "city": "Солнечногорск", "street": "Лесная",
             "house": "5А", "apartment": "  ", "recipient_name": "Антон Иванов"}
            """;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserAddressRepository addressRepository;
    @Autowired
    private UserRepository userRepository;

    private MockMvc mvc;
    private String anton;
    private String maria;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        anton = TestUsers.customer(mvc, "anton@example.com");
        maria = TestUsers.customer(mvc, "maria@example.com");
    }

    @AfterEach
    void cleanUp() {
        addressRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- access ---

    @Test
    void everyEndpointNeedsToken() throws Exception {
        Long id = createAndGetId(anton, HOME);

        mvc.perform(get("/users/me/addresses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me/addresses/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/users/me/addresses").contentType(MediaType.APPLICATION_JSON).content(DACHA))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/users/me/addresses/{id}", id).contentType(MediaType.APPLICATION_JSON).content(DACHA))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/users/me/addresses/{id}", id)).andExpect(status().isUnauthorized());

        assertThat(addressRepository.count()).isEqualTo(1);
    }

    @Test
    void addressOfAnotherUserIsNotFound() throws Exception {
        Long id = createAndGetId(anton, HOME);

        mvc.perform(get("/users/me/addresses").header("Authorization", TestUsers.bearer(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(maria)))
                .andExpect(status().isNotFound())
                .andExpect(status().reason("Address " + id + " not found"));
        mvc.perform(put("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(maria))
                        .contentType(MediaType.APPLICATION_JSON).content(DACHA))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(maria)))
                .andExpect(status().isNotFound());

        assertThat(addressRepository.findById(id).orElseThrow().getCity()).isEqualTo("Москва");
    }

    // --- create and read ---

    @Test
    void createTrimsValuesAndReturnsLocation() throws Exception {
        create(anton, HOME)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/users/me/addresses/" + onlyAddressId())))
                .andExpect(jsonPath("$.postal_code").value("101000"))
                .andExpect(jsonPath("$.city").value("Москва"))
                .andExpect(jsonPath("$.house").value("12к2"))
                .andExpect(jsonPath("$.apartment").value("45"))
                .andExpect(jsonPath("$.recipient_name").value("Антон Иванов"))
                .andExpect(jsonPath("$.courier_note").value("подъезд 3, домофон 45"))
                .andExpect(jsonPath("$.created_at").isString());
    }

    @Test
    void blankOptionalFieldsAreStoredAsNull() throws Exception {
        create(anton, DACHA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apartment").doesNotExist())
                .andExpect(jsonPath("$.courier_note").doesNotExist());

        var saved = addressRepository.findAll().getFirst();
        assertThat(saved.getApartment()).isNull();
        assertThat(saved.getCourierNote()).isNull();
    }

    @Test
    void listReturnsOwnAddressesInOrderOfCreation() throws Exception {
        Long home = createAndGetId(anton, HOME);
        Long dacha = createAndGetId(anton, DACHA);
        createAndGetId(maria, HOME);

        mvc.perform(get("/users/me/addresses").header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(home))
                .andExpect(jsonPath("$[1].id").value(dacha));
        mvc.perform(get("/users/me/addresses/{id}", dacha).header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Солнечногорск"));
    }

    @Test
    void createValidatesBody() throws Exception {
        create(anton, "{}").andExpect(status().isBadRequest());
        create(anton, HOME.replace("\"Москва\"", "\"  \"")).andExpect(status().isBadRequest());
        create(anton, HOME.replace("\"12к2\"", "\"" + "1".repeat(21) + "\"")).andExpect(status().isBadRequest());
        create(anton, HOME.replace("\"Антон Иванов\"", "null")).andExpect(status().isBadRequest());
        create(anton, HOME.replace("\"подъезд 3, домофон 45\"", "\"" + "x".repeat(501) + "\""))
                .andExpect(status().isBadRequest());

        assertThat(addressRepository.count()).isZero();
    }

    @Test
    void numberOfAddressesIsLimited() throws Exception {
        for (int i = 0; i < AddressService.MAX_ADDRESSES; i++) {
            create(anton, DACHA).andExpect(status().isCreated());
        }

        create(anton, DACHA).andExpect(status().isConflict());
        // the limit is per user
        create(maria, DACHA).andExpect(status().isCreated());
    }

    // --- update and delete ---

    @Test
    void updateReplacesAllFields() throws Exception {
        Long id = createAndGetId(anton, HOME);

        mvc.perform(put("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(anton))
                        .contentType(MediaType.APPLICATION_JSON).content(DACHA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.city").value("Солнечногорск"))
                .andExpect(jsonPath("$.apartment").doesNotExist())
                .andExpect(jsonPath("$.courier_note").doesNotExist());

        var saved = addressRepository.findById(id).orElseThrow();
        assertThat(saved.getCity()).isEqualTo("Солнечногорск");
        assertThat(saved.getCourierNote()).isNull();
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
    }

    @Test
    void updateValidatesBodyAndReturns404ForUnknownId() throws Exception {
        Long id = createAndGetId(anton, HOME);

        mvc.perform(put("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(anton))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/users/me/addresses/{id}", id + 1000).header("Authorization", TestUsers.bearer(anton))
                        .contentType(MediaType.APPLICATION_JSON).content(DACHA))
                .andExpect(status().isNotFound());

        assertThat(addressRepository.findById(id).orElseThrow().getCity()).isEqualTo("Москва");
    }

    @Test
    void deleteRemovesAddress() throws Exception {
        Long id = createAndGetId(anton, HOME);

        mvc.perform(delete("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/users/me/addresses/{id}", id).header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isNotFound());

        assertThat(addressRepository.count()).isZero();
    }

    // --- active address ---

    @Test
    void firstAddressBecomesActiveOthersDoNot() throws Exception {
        create(anton, HOME).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));
        create(anton, DACHA).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false));
        // per user: Maria's first address is active too
        create(maria, DACHA).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void activateSwitchesActiveAddress() throws Exception {
        Long home = createAndGetId(anton, HOME);
        Long dacha = createAndGetId(anton, DACHA);

        activate(anton, dacha)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dacha))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/users/me/addresses").header("Authorization", TestUsers.bearer(anton)))
                .andExpect(jsonPath("$[0].id").value(home))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[1].active").value(true));

        // repeating changes nothing
        activate(anton, dacha).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        assertThat(activeIds()).containsExactly(dacha);
    }

    @Test
    void activateNeedsTokenAndOwnAddress() throws Exception {
        Long home = createAndGetId(anton, HOME);
        Long dacha = createAndGetId(anton, DACHA);

        mvc.perform(put("/users/me/addresses/{id}/active", dacha)).andExpect(status().isUnauthorized());
        activate(maria, dacha).andExpect(status().isNotFound());
        activate(anton, dacha + 1000).andExpect(status().isNotFound());

        assertThat(activeIds()).containsExactly(home);
    }

    @Test
    void updateKeepsActiveFlag() throws Exception {
        Long home = createAndGetId(anton, HOME);

        mvc.perform(put("/users/me/addresses/{id}", home).header("Authorization", TestUsers.bearer(anton))
                        .contentType(MediaType.APPLICATION_JSON).content(DACHA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void deletingActiveAddressActivatesOldestRemaining() throws Exception {
        Long home = createAndGetId(anton, HOME);
        Long dacha = createAndGetId(anton, DACHA);
        Long work = createAndGetId(anton, HOME);
        activate(anton, work).andExpect(status().isOk());

        // deleting an inactive one leaves the active as it is
        mvc.perform(delete("/users/me/addresses/{id}", dacha).header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isNoContent());
        assertThat(activeIds()).containsExactly(work);

        mvc.perform(delete("/users/me/addresses/{id}", work).header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isNoContent());
        assertThat(activeIds()).containsExactly(home);

        mvc.perform(delete("/users/me/addresses/{id}", home).header("Authorization", TestUsers.bearer(anton)))
                .andExpect(status().isNoContent());
        assertThat(addressRepository.count()).isZero();
    }

    private ResultActions activate(String token, Long id) throws Exception {
        return mvc.perform(put("/users/me/addresses/{id}/active", id).header("Authorization", TestUsers.bearer(token)));
    }

    private java.util.List<Long> activeIds() {
        return addressRepository.findAll().stream().filter(a -> a.isActive()).map(a -> a.getId()).toList();
    }

    private ResultActions create(String token, String body) throws Exception {
        return mvc.perform(post("/users/me/addresses").header("Authorization", TestUsers.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Long createAndGetId(String token, String body) throws Exception {
        String response = create(token, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private Long onlyAddressId() {
        return addressRepository.findAll().getFirst().getId();
    }

}
