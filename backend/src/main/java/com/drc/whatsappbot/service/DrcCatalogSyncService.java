package com.drc.whatsappbot.service;

import com.drc.whatsappbot.config.BotProperties;
import com.drc.whatsappbot.dto.CatalogSyncResponse;
import com.drc.whatsappbot.dto.StoreRulesDto;
import com.drc.whatsappbot.model.CatalogProduct;
import com.drc.whatsappbot.repository.CatalogProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DrcCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(DrcCatalogSyncService.class);

    private static final Map<String, String> SYNONYM_HINTS = Map.ofEntries(
            Map.entry("atta", "atta,aata,gehun,wheat flour,aashirvad,ashirwad,aashirvaad,flour,roti atta"),
            Map.entry("rice", "chawal,rice,basmati,india gate,bhat"),
            Map.entry("toor dal", "toor dal,arhar dal,tuar dal,dal,daal,arhar,toor"),
            Map.entry("moong dal", "moong dal,mung dal,dal,daal,yellow dal,moong"),
            Map.entry("oil", "tel,oil,cooking oil,sunflower oil,fortune,refined oil"),
            Map.entry("masala", "garam masala,masala,everest,spices,spice"),
            Map.entry("salt", "namak,salt,tata salt,iodised salt"),
            Map.entry("parle", "biscuit,biscuits,parle,parle-g,parleg,biskut"),
            Map.entry("lays", "chips,lays,lay's,wafer,wafers,magic masala"),
            Map.entry("bhujia", "bhujia,bhujiya,namkeen,sev,haldiram"),
            Map.entry("butter", "makkhan,butter,amul butter,maska"),
            Map.entry("corn flakes", "cornflakes,corn flakes,cereal,breakfast,kellogg,kelloggs"),
            Map.entry("tea", "chai,tea,chai patti,tata tea,tea gold,patti"),
            Map.entry("surf", "surf,surf excel,detergent,washing powder,kapde ka powder"),
            Map.entry("colgate", "toothpaste,paste,manjan,colgate,tooth paste"),
            Map.entry("closeup", "toothpaste,paste,closeup,close up,manjan"),
            Map.entry("dettol", "sabun,soap,dettol,bath soap,bathing soap")
    );

    private record SeedItem(Long fallbackId, String name, String desc, String price, int stock,
                            String unit, String category, String source, String synonyms) {}

    private static final List<SeedItem> DRC_FALLBACK_ITEMS = List.of(
            new SeedItem(1L, "Aashirvaad Atta 5 kg", "Whole wheat flour for soft rotis.", "265.00", 40, "bag", "Atta, Rice & Dal", "DRC_FALLBACK", SYNONYM_HINTS.get("atta")),
            new SeedItem(2L, "India Gate Basmati Rice 5 kg", "Long-grain aged basmati.", "620.00", 25, "bag", "Atta, Rice & Dal", "DRC_FALLBACK", SYNONYM_HINTS.get("rice")),
            new SeedItem(3L, "Toor Dal 1 kg", "Unpolished arhar dal.", "165.00", 30, "kg", "Atta, Rice & Dal", "DRC_FALLBACK", SYNONYM_HINTS.get("toor dal")),
            new SeedItem(4L, "Moong Dal 500 g", "Split yellow moong.", "78.00", 28, "pack", "Atta, Rice & Dal", "DRC_FALLBACK", SYNONYM_HINTS.get("moong dal")),
            new SeedItem(5L, "Fortune Sunflower Oil 1 L", "Refined sunflower cooking oil.", "148.00", 50, "bottle", "Oil & Masala", "DRC_FALLBACK", SYNONYM_HINTS.get("oil")),
            new SeedItem(6L, "Everest Garam Masala 100 g", "Blended whole-spice masala.", "92.00", 35, "pack", "Oil & Masala", "DRC_FALLBACK", SYNONYM_HINTS.get("masala")),
            new SeedItem(7L, "Tata Salt 1 kg", "Vacuum evaporated iodised salt.", "28.00", 80, "pack", "Oil & Masala", "DRC_FALLBACK", SYNONYM_HINTS.get("salt")),
            new SeedItem(8L, "Parle-G Biscuits 800 g", "The classic glucose biscuit.", "70.00", 60, "pack", "Snacks & Biscuits", "DRC_FALLBACK", SYNONYM_HINTS.get("parle")),
            new SeedItem(9L, "Lay's Magic Masala 52 g", "Crunchy masala potato chips.", "20.00", 100, "pack", "Snacks & Biscuits", "DRC_FALLBACK", SYNONYM_HINTS.get("lays")),
            new SeedItem(10L, "Haldiram's Bhujia 400 g", "Spicy besan sev.", "125.00", 25, "pack", "Snacks & Biscuits", "DRC_FALLBACK", SYNONYM_HINTS.get("bhujia")),
            new SeedItem(11L, "Amul Butter 500 g", "Pasteurised table butter.", "285.00", 18, "pack", "Dairy & Breakfast", "DRC_FALLBACK", SYNONYM_HINTS.get("butter")),
            new SeedItem(12L, "Kellogg's Corn Flakes 475 g", "Ready-to-eat breakfast cereal.", "190.00", 18, "box", "Dairy & Breakfast", "DRC_FALLBACK", SYNONYM_HINTS.get("corn flakes")),
            new SeedItem(13L, "Tata Tea Gold 500 g", "Premium blend of Assam tea.", "310.00", 22, "pack", "Dairy & Breakfast", "DRC_FALLBACK", SYNONYM_HINTS.get("tea")),
            new SeedItem(14L, "Surf Excel Easy Wash 1 kg", "Detergent powder.", "135.00", 45, "kg", "Home & Personal Care", "DRC_FALLBACK", SYNONYM_HINTS.get("surf")),
            new SeedItem(15L, "Colgate Strong Teeth 200 g", "Calcium-boost toothpaste.", "108.00", 55, "tube", "Home & Personal Care", "DRC_FALLBACK", SYNONYM_HINTS.get("colgate")),
            new SeedItem(16L, "Dettol Soap 4 x 125 g", "Original antibacterial bathing soap.", "185.00", 29, "pack", "Home & Personal Care", "DRC_FALLBACK", SYNONYM_HINTS.get("dettol")),
            new SeedItem(17L, "Closeup", "Fresh gel toothpaste.", "20.00", 19, "pcs", "Home & Personal Care", "DRC_FALLBACK", SYNONYM_HINTS.get("closeup"))
    );

    private static final List<SeedItem> LOCAL_KIRANA_STAPLES = List.of(
            new SeedItem(null, "Amul Taaza Milk 500 ml", "Fresh pasteurised toned milk pouch.", "28.00", 60, "packet", "Dairy & Breakfast", "LOCAL_SUPPLEMENT", "amul milk,milk,doodh,dudh,amul doodh,taaza,taza milk,packet milk"),
            new SeedItem(null, "Harvest Gold Brown Bread 400 g", "Whole wheat soft brown bread.", "50.00", 35, "pack", "Dairy & Breakfast", "LOCAL_SUPPLEMENT", "brown bread,bread,double roti,pav,wheat bread,harvest gold"),
            new SeedItem(null, "Mother Dairy Curd 400 g", "Fresh thick dahi cup.", "40.00", 30, "cup", "Dairy & Breakfast", "LOCAL_SUPPLEMENT", "curd,dahi,yogurt,mother dairy dahi"),
            new SeedItem(null, "Maggi 2-Minute Noodles 280 g", "4-pack masala instant noodles.", "56.00", 48, "pack", "Snacks & Biscuits", "LOCAL_SUPPLEMENT", "maggi,maggie,noodles,instant noodles"),
            new SeedItem(null, "Uttam Sugar (Cheeni) 1 kg", "Sulphur-free crystal sugar.", "52.00", 40, "kg", "Atta, Rice & Dal", "LOCAL_SUPPLEMENT", "sugar,cheeni,chini,shakkar")
    );

    private final CatalogProductRepository catalogRepo;
    private final BotProperties props;
    private final RestClient restClient;
    private final AtomicReference<StoreRulesDto> storeRules = new AtomicReference<>(
            new StoreRulesDto(5.0, new BigDecimal("100"), new BigDecimal("30"), new BigDecimal("500"), false)
    );
    private volatile LocalDateTime lastSyncedAt;
    private volatile boolean lastLiveSyncOk;

    @Autowired
    public DrcCatalogSyncService(CatalogProductRepository catalogRepo, BotProperties props) {
        this(catalogRepo, props, buildDefaultRestClient(props));
    }

    public DrcCatalogSyncService(CatalogProductRepository catalogRepo,
                                 BotProperties props,
                                 RestClient restClient) {
        this.catalogRepo = catalogRepo;
        this.props = props;
        this.restClient = restClient;
    }

    private static RestClient buildDefaultRestClient(BotProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getDrc().getTimeoutMs());
        rf.setReadTimeout(props.getDrc().getTimeoutMs());
        return RestClient.builder()
                .baseUrl(props.getDrc().getApiBaseUrl())
                .requestFactory(rf)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.getDrc().isSyncOnStartup()) {
            try {
                syncCatalog(props.getDrc().isIncludeLocalStaples());
            } catch (Exception ex) {
                log.warn("Startup DRC sync fell back to local catalog: {}", ex.getMessage());
            }
        } else if (catalogRepo.count() == 0) {
            seedFallbackCatalog(props.getDrc().isIncludeLocalStaples());
        }
    }

    @Scheduled(fixedDelayString = "${bot.drc.sync-interval-ms:900000}", initialDelayString = "${bot.drc.sync-interval-ms:900000}")
    public void scheduledSync() {
        if (!props.getDrc().isSyncOnStartup()) {
            return;
        }
        try {
            syncCatalog(props.getDrc().isIncludeLocalStaples());
        } catch (Exception ex) {
            log.debug("Scheduled DRC catalog sync failed: {}", ex.getMessage());
        }
    }

    @Transactional
    public CatalogSyncResponse syncCatalog(boolean includeLocalStaples) {
        boolean liveSuccess = false;
        int liveCount = 0;
        String message;

        try {
            JsonNode storeNode = restClient.get().uri("/api/store").retrieve().body(JsonNode.class);
            if (storeNode != null && storeNode.has("deliveryFee")) {
                storeRules.set(new StoreRulesDto(
                        storeNode.path("deliveryRadiusKm").asDouble(5.0),
                        new BigDecimal(storeNode.path("minOrderValue").asText("100")),
                        new BigDecimal(storeNode.path("deliveryFee").asText("30")),
                        new BigDecimal(storeNode.path("freeDeliveryAbove").asText("500")),
                        true
                ));
            }

            LocalDateTime now = LocalDateTime.now();
            Set<Long> syncedExternalIds = new HashSet<>();
            int page = 0;
            boolean hasMore = true;
            while (hasMore && page < 20) {
                int currentPage = page;
                JsonNode productsPage = restClient.get()
                        .uri("/api/products?page=" + currentPage + "&size=100")
                        .retrieve()
                        .body(JsonNode.class);
                JsonNode content = productsPage == null ? null : productsPage.get("content");
                if (content == null || !content.isArray() || content.isEmpty()) {
                    break;
                }
                for (JsonNode p : content) {
                    long extId = p.path("id").asLong();
                    String name = p.path("name").asText();
                    CatalogProduct entity = catalogRepo.findByExternalDrcId(extId)
                            .or(() -> catalogRepo.findByNameIgnoreCase(name))
                            .orElseGet(CatalogProduct::new);
                    entity.setExternalDrcId(extId);
                    entity.setName(name);
                    entity.setDescription(p.path("description").asText(""));
                    entity.setPrice(new BigDecimal(p.path("price").asText("0.00")));
                    entity.setStock(p.path("stock").asInt(0));
                    entity.setUnit(p.path("unit").asText("pcs"));
                    entity.setCategoryName(p.path("categoryName").asText("General"));
                    entity.setImageUrl(p.path("imageUrl").isNull() ? null : p.path("imageUrl").asText(null));
                    entity.setSource("LIVE_DRC");
                    entity.setSynonyms(inferSynonyms(name));
                    entity.setSyncedAt(now);
                    catalogRepo.save(entity);
                    syncedExternalIds.add(extId);
                    liveCount++;
                }
                liveSuccess = true;
                boolean isLast = productsPage.path("last").asBoolean(true);
                int totalPages = productsPage.path("totalPages").asInt(1);
                hasMore = !isLast && (currentPage + 1 < totalPages);
                page++;
            }

            if (liveSuccess && !syncedExternalIds.isEmpty()) {
                List<CatalogProduct> stale = catalogRepo.findBySource("LIVE_DRC").stream()
                        .filter(p -> p.getExternalDrcId() == null || !syncedExternalIds.contains(p.getExternalDrcId()))
                        .toList();
                if (!stale.isEmpty()) {
                    catalogRepo.deleteAll(stale);
                }
            }
            message = "Synced " + liveCount + " products and live delivery rules from " + props.getDrc().getApiBaseUrl();
        } catch (Exception ex) {
            log.info("Live DRC API unreachable ({}), using local DRC fallback catalog", ex.getMessage());
            seedFallbackIfEmpty();
            message = "Live DRC API unreachable (" + ex.getMessage() + "). Using local DRC catalog fallback.";
        }

        if (!includeLocalStaples) {
            List<CatalogProduct> localOnes = catalogRepo.findBySource("LOCAL_SUPPLEMENT");
            if (!localOnes.isEmpty()) {
                catalogRepo.deleteAll(localOnes);
            }
        } else {
            ensureLocalStaples();
        }

        lastSyncedAt = LocalDateTime.now();
        lastLiveSyncOk = liveSuccess;

        int localCount = catalogRepo.findBySource("LOCAL_SUPPLEMENT").size();
        int totalCount = (int) catalogRepo.count();

        return new CatalogSyncResponse(
                liveSuccess,
                props.getDrc().getApiBaseUrl(),
                liveCount,
                localCount,
                totalCount,
                storeRules.get(),
                lastSyncedAt,
                message
        );
    }

    @Transactional
    public void seedFallbackCatalog(boolean includeLocalStaples) {
        seedFallbackIfEmpty();
        if (includeLocalStaples) {
            ensureLocalStaples();
        }
        lastSyncedAt = LocalDateTime.now();
    }

    private void seedFallbackIfEmpty() {
        if (catalogRepo.count() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (SeedItem item : DRC_FALLBACK_ITEMS) {
            CatalogProduct entity = new CatalogProduct();
            entity.setExternalDrcId(item.fallbackId());
            entity.setName(item.name());
            entity.setDescription(item.desc());
            entity.setPrice(new BigDecimal(item.price()));
            entity.setStock(item.stock());
            entity.setUnit(item.unit());
            entity.setCategoryName(item.category());
            entity.setSource(item.source());
            entity.setSynonyms(item.synonyms());
            entity.setSyncedAt(now);
            catalogRepo.save(entity);
        }
    }

    private void ensureLocalStaples() {
        LocalDateTime now = LocalDateTime.now();
        for (SeedItem item : LOCAL_KIRANA_STAPLES) {
            boolean alreadyExists = catalogRepo.findByNameIgnoreCase(item.name()).isPresent();
            if (!alreadyExists) {
                CatalogProduct entity = new CatalogProduct();
                entity.setName(item.name());
                entity.setDescription(item.desc());
                entity.setPrice(new BigDecimal(item.price()));
                entity.setStock(item.stock());
                entity.setUnit(item.unit());
                entity.setCategoryName(item.category());
                entity.setSource(item.source());
                entity.setSynonyms(item.synonyms());
                entity.setSyncedAt(now);
                catalogRepo.save(entity);
            }
        }
    }

    private String inferSynonyms(String productName) {
        String lower = productName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : SYNONYM_HINTS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                if (!sb.isEmpty()) sb.append(",");
                sb.append(entry.getValue());
            }
        }
        return sb.toString();
    }

    public StoreRulesDto getStoreRules() {
        return storeRules.get();
    }

    public List<CatalogProduct> getAllProducts() {
        return catalogRepo.findAll();
    }

    public boolean isLastLiveSyncOk() {
        return lastLiveSyncOk;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }
}

