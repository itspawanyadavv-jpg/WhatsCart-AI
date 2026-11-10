package com.drc.whatsappbot;

import com.drc.whatsappbot.dto.ChatTurnResponse;
import com.drc.whatsappbot.enums.MessageChannel;
import com.drc.whatsappbot.service.ConversationEngineService;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "bot.security.allow-simulate-pay=false",
        "bot.security.admin-api-key=secret-admin-key"
})
class OrderManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationEngineService engine;

    @Autowired
    private DrcCatalogSyncService catalogService;

    @BeforeEach
    void init() {
        catalogService.seedFallbackCatalog(true);
    }

    @Test
    void enforcesAdminKeyAndSimulatePayGuardAndOtpOnDelivery() throws Exception {
        String phone = "+919833334444";
        engine.resetSession(phone, "Rohan");
        engine.handleInboundMessage(phone, "Rohan", "5kg Aashirvaad atta deliver at Sector 14", MessageChannel.SIMULATOR);
        ChatTurnResponse upiTurn = engine.handleInboundMessage(phone, "Rohan", "UPI", MessageChannel.SIMULATOR);
        String orderCode = upiTurn.activeOrder().getOrderCode();
        String otp = upiTurn.activeOrder().getDeliveryOtp();

        // Missing X-Admin-Key -> 403 Forbidden
        mockMvc.perform(patch("/api/orders/" + orderCode + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PACKING\",\"riderName\":\"Ramesh\"}"))
                .andExpect(status().isForbidden());

        // Valid X-Admin-Key -> simulate-pay still blocked because allow-simulate-pay=false
        mockMvc.perform(post("/api/orders/" + orderCode + "/simulate-pay")
                        .header("X-Admin-Key", "secret-admin-key"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("disabled")));

        // Wrong OTP when marking DELIVERED -> 400 Bad Request
        mockMvc.perform(patch("/api/orders/" + orderCode + "/status")
                        .header("X-Admin-Key", "secret-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\",\"riderName\":\"Ramesh\",\"otp\":\"9999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid delivery OTP")));

        // Correct OTP -> 200 OK
        mockMvc.perform(patch("/api/orders/" + orderCode + "/status")
                        .header("X-Admin-Key", "secret-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\",\"riderName\":\"Ramesh\",\"otp\":\"" + otp + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"DELIVERED\"")));

        // Listing without X-Admin-Key -> 403 Forbidden; with X-Admin-Key -> 200 OK
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/orders").header("X-Admin-Key", "secret-admin-key"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(orderCode)));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"allowSimulatePay\":false")));

        mockMvc.perform(post("/api/payments/checkout/confirm").param("orderCode", orderCode))
                .andExpect(status().isForbidden());
    }
}

