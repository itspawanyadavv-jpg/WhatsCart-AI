package com.drc.whatsappbot;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.LlmParseResult;
import com.drc.whatsappbot.enums.ConversationState;
import com.drc.whatsappbot.enums.IntentType;
import com.drc.whatsappbot.service.LlmHinglishParserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmHinglishParserServiceTest {

    private LlmHinglishParserService parser;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.getLlm().setProvider("FALLBACK");
        parser = new LlmHinglishParserService(props, new ObjectMapper());
    }

    @Test
    void parsesHeroHinglishGroceryListCorrectly() {
        String text = "Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do";
        LlmParseResult res = parser.parse(text, ConversationState.IDLE);

        assertThat(res.intent()).isEqualTo(IntentType.ORDER);
        assertThat(res.items()).hasSize(3);

        assertThat(res.items().get(0).quantity()).isEqualTo(2);
        assertThat(res.items().get(0).productQuery()).isEqualToIgnoringCase("Amul milk");

        assertThat(res.items().get(1).quantity()).isEqualTo(1);
        assertThat(res.items().get(1).productQuery()).isEqualToIgnoringCase("brown bread");

        assertThat(res.items().get(2).quantity()).isEqualTo(1);
        assertThat(res.items().get(2).packSizeMention()).isEqualToIgnoringCase("5kg");
        assertThat(res.items().get(2).productQuery()).isEqualToIgnoringCase("Aashirvaad atta");
    }

    @Test
    void parsesHindiNumbersAndInlineAddress() {
        String text = "do bottle fortune tel aur ek tata namak bhejo at Flat 402, Krishna Heights, Sector 12";
        LlmParseResult res = parser.parse(text, ConversationState.IDLE);

        assertThat(res.intent()).isEqualTo(IntentType.ORDER);
        assertThat(res.deliveryAddress()).isEqualTo("Flat 402, Krishna Heights, Sector 12");
        assertThat(res.items()).hasSize(2);
        assertThat(res.items().get(0).quantity()).isEqualTo(2);
        assertThat(res.items().get(1).quantity()).isEqualTo(1);
    }

    @Test
    void recognizesAddressWhenAwaitingAddress() {
        LlmParseResult res = parser.parse("House 18, Block B, Green Park", ConversationState.AWAITING_ADDRESS);
        assertThat(res.intent()).isEqualTo(IntentType.SET_ADDRESS);
        assertThat(res.deliveryAddress()).isEqualTo("House 18, Block B, Green Park");
    }

    @Test
    void recognizesUpiAndCodIntents() {
        assertThat(parser.parse("Pay via UPI", ConversationState.AWAITING_PAYMENT).intent()).isEqualTo(IntentType.PAY_UPI);
        assertThat(parser.parse("cash on delivery", ConversationState.AWAITING_PAYMENT).intent()).isEqualTo(IntentType.PAY_COD);
    }

    @Test
    void distinguishesCancelOrderClearCartRemoveItemsAndOrderLookup() {
        assertThat(parser.parse("cancel order", ConversationState.IDLE).intent()).isEqualTo(IntentType.CANCEL_ORDER);
        assertThat(parser.parse("clear cart", ConversationState.REVIEWING_CART).intent()).isEqualTo(IntentType.CLEAR_CART);
        assertThat(parser.parse("DRC-WA-4242", ConversationState.IDLE).intent()).isEqualTo(IntentType.CHECK_STATUS);

        LlmParseResult rem = parser.parse("remove 1 Amul milk", ConversationState.REVIEWING_CART);
        assertThat(rem.intent()).isEqualTo(IntentType.REMOVE_ITEMS);
        assertThat(rem.items()).hasSize(1);
        assertThat(rem.items().get(0).quantity()).isEqualTo(1);
        assertThat(rem.items().get(0).rawText()).isEqualTo("1 amul milk");

        LlmParseResult upd1 = parser.parse("make Amul milk 4", ConversationState.REVIEWING_CART);
        assertThat(upd1.intent()).isEqualTo(IntentType.UPDATE_QUANTITY);
        assertThat(upd1.items().get(0).quantity()).isEqualTo(4);
        assertThat(upd1.items().get(0).productQuery()).isEqualToIgnoringCase("Amul milk");

        LlmParseResult upd2 = parser.parse("Amul milk 2 kar do", ConversationState.REVIEWING_CART);
        assertThat(upd2.intent()).isEqualTo(IntentType.UPDATE_QUANTITY);
        assertThat(upd2.items().get(0).quantity()).isEqualTo(2);

        assertThat(parser.parse("repeat last order", ConversationState.IDLE).intent()).isEqualTo(IntentType.REORDER);
        assertThat(parser.parse("my orders", ConversationState.IDLE).intent()).isEqualTo(IntentType.ORDER_HISTORY);
    }

    @Test
    void coolsDownOpenAiAfter401UnauthorizedAndFallsBackCleanly() {
        BotProperties props = new BotProperties();
        props.getLlm().setProvider("OPENAI");
        props.getLlm().setOpenaiApiKey("invalid-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        LlmHinglishParserService openaiParser = new LlmHinglishParserService(props, new ObjectMapper(), builder.build());

        // First call gets 401 and falls back
        LlmParseResult first = openaiParser.parse("2 packet Amul milk", ConversationState.IDLE);
        assertThat(first.parserUsed()).isEqualTo("HINGLISH_NLP_FALLBACK");
        assertThat(first.items()).hasSize(1);

        // Second call skips OpenAI because of cooldown (MockRestServiceServer only expects 1 call)
        LlmParseResult second = openaiParser.parse("1 brown bread", ConversationState.IDLE);
        assertThat(second.parserUsed()).isEqualTo("HINGLISH_NLP_FALLBACK");
        server.verify();
    }

    @Test
    void parsesGeminiJsonResponseWhenApiKeyConfigured() {
        BotProperties props = new BotProperties();
        props.getLlm().setProvider("GEMINI");
        props.getLlm().setGeminiApiKey("fake-gemini-key");
        props.getLlm().setGeminiModel("gemini-2.5-flash");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=fake-gemini-key"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {
                              "parts": [{
                                "text": "{\\"intent\\":\\"ORDER\\",\\"items\\":[{\\"rawText\\":\\"2 doodh\\",\\"productQuery\\":\\"Amul milk\\",\\"quantity\\":2,\\"unit\\":\\"packet\\",\\"packSizeMention\\":null}],\\"deliveryAddress\\":\\"Sector 12\\",\\"normalizedSummary\\":\\"2x Amul milk\\"}"
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        LlmHinglishParserService geminiParser = new LlmHinglishParserService(props, new ObjectMapper(), builder.build());
        LlmParseResult res = geminiParser.parse("2 doodh sector 12 bhejo", ConversationState.IDLE);

        server.verify();
        assertThat(res.parserUsed()).startsWith("GEMINI");
        assertThat(res.intent()).isEqualTo(IntentType.ORDER);
        assertThat(res.deliveryAddress()).isEqualTo("Sector 12");
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).quantity()).isEqualTo(2);
    }
}

