package com.drc.whatsappbot;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.CatalogSyncResponse;
import com.drc.whatsappbot.model.CatalogProduct;
import com.drc.whatsappbot.repository.CatalogProductRepository;
import com.drc.whatsappbot.service.DrcCatalogSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
class DrcCatalogSyncServiceTest {

    @Autowired
    private CatalogProductRepository catalogRepo;

    @BeforeEach
    void clearCatalog() {
        catalogRepo.deleteAll();
    }

    @Test
    void syncsPaginatedLiveProductsAndPrunesStaleLiveItems() {
        BotProperties props = new BotProperties();
        props.getDrc().setApiBaseUrl("http://mock-drc");

        // Pre-seed a stale LIVE_DRC item that no longer exists on the remote store
        CatalogProduct stale = new CatalogProduct();
        stale.setExternalDrcId(999L);
        stale.setName("Discontinued Item");
        stale.setPrice(new java.math.BigDecimal("10.00"));
        stale.setStock(5);
        stale.setSource("LIVE_DRC");
        catalogRepo.save(stale);

        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-drc");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://mock-drc/api/store"))
                .andRespond(withSuccess("""
                        {"deliveryRadiusKm": 7.5, "minOrderValue": 150, "deliveryFee": 25, "freeDeliveryAbove": 600}
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("http://mock-drc/api/products?page=0&size=100"))
                .andRespond(withSuccess("""
                        {
                          "content": [
                            {"id": 101, "name": "Aashirvaad Atta 5 kg", "description": "Wheat flour", "price": 270.00, "stock": 20, "unit": "bag", "categoryName": "Staples"}
                          ],
                          "last": false,
                          "totalPages": 2
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("http://mock-drc/api/products?page=1&size=100"))
                .andRespond(withSuccess("""
                        {
                          "content": [
                            {"id": 102, "name": "Tata Salt 1 kg", "description": "Salt", "price": 28.00, "stock": 50, "unit": "pack", "categoryName": "Staples"}
                          ],
                          "last": true,
                          "totalPages": 2
                        }
                        """, MediaType.APPLICATION_JSON));

        DrcCatalogSyncService service = new DrcCatalogSyncService(catalogRepo, props, builder.build());
        CatalogSyncResponse res = service.syncCatalog(true);

        server.verify();
        assertThat(res.liveSyncSucceeded()).isTrue();
        assertThat(res.liveDrcCount()).isEqualTo(2);
        assertThat(res.localSupplementCount()).isEqualTo(5);
        assertThat(res.storeRules().deliveryFee()).isEqualByComparingTo("25");
        assertThat(res.storeRules().freeDeliveryAbove()).isEqualByComparingTo("600");
        assertThat(catalogRepo.findByExternalDrcId(999L)).isEmpty();
        assertThat(catalogRepo.findByExternalDrcId(102L)).isPresent();
    }

    @Test
    void fallsBackToLocalCatalogWhenDrcApiFails() {
        BotProperties props = new BotProperties();
        props.getDrc().setApiBaseUrl("http://mock-drc");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-drc");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mock-drc/api/store")).andRespond(withServerError());

        DrcCatalogSyncService service = new DrcCatalogSyncService(catalogRepo, props, builder.build());
        CatalogSyncResponse res = service.syncCatalog(false);

        server.verify();
        assertThat(res.liveSyncSucceeded()).isFalse();
        assertThat(res.totalCatalogCount()).isEqualTo(17);
        assertThat(res.localSupplementCount()).isZero();
    }
}

