package com.drc.whatsappbot;

import com.drc.whatsappbot.dto.ParsedItem;
import com.drc.whatsappbot.model.CatalogProduct;
import com.drc.whatsappbot.service.FuzzyCatalogMatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzyCatalogMatcherServiceTest {

    private FuzzyCatalogMatcherService matcher;
    private List<CatalogProduct> catalog;

    @BeforeEach
    void setUp() {
        matcher = new FuzzyCatalogMatcherService();
        catalog = List.of(
                product(1L, "Aashirvaad Atta 5 kg", "265.00", 40, "bag", "Atta, Rice & Dal", "atta,aashirvad,ashirwad,aata,gehun"),
                product(2L, "Amul Taaza Milk 500 ml", "28.00", 60, "packet", "Dairy & Breakfast", "amul milk,milk,doodh,dudh,amul doodh"),
                product(3L, "Harvest Gold Brown Bread 400 g", "50.00", 35, "pack", "Dairy & Breakfast", "brown bread,bread"),
                product(4L, "Toor Dal 1 kg", "165.00", 30, "kg", "Atta, Rice & Dal", "toor dal,arhar dal,dal"),
                product(5L, "Haldiram's Bhujia 400 g", "125.00", 0, "pack", "Snacks & Biscuits", "bhujia,bhujiya,namkeen")
        );
    }

    @Test
    void matchesHinglishSynonymsAndTyposAndPackSizes() {
        var milkMatch = matcher.match(new ParsedItem("2 packet Amul doodh", "amul doodh", 2, "packet", null), catalog);
        assertThat(milkMatch.result().matched()).isTrue();
        assertThat(milkMatch.result().matchedProductName()).isEqualTo("Amul Taaza Milk 500 ml");
        assertThat(milkMatch.effectiveQty()).isEqualTo(2);

        // 5kg matches the 5 kg pack -> effective quantity = 1 bag, not 5 bags!
        var attaMatch = matcher.match(new ParsedItem("5kg ashirwad atta", "ashirwad atta", 1, "kg", "5kg"), catalog);
        assertThat(attaMatch.result().matched()).isTrue();
        assertThat(attaMatch.result().matchedProductName()).isEqualTo("Aashirvaad Atta 5 kg");
        assertThat(attaMatch.effectiveQty()).isEqualTo(1);

        // 2kg Toor Dal against 1 kg pack -> 2 units
        var dalMatch = matcher.match(new ParsedItem("2kg arhar dal", "arhar dal", 1, "kg", "2kg"), catalog);
        assertThat(dalMatch.result().matched()).isTrue();
        assertThat(dalMatch.result().matchedProductName()).isEqualTo("Toor Dal 1 kg");
        assertThat(dalMatch.effectiveQty()).isEqualTo(2);
    }

    @Test
    void detectsOutOfStockItems() {
        var bhujia = matcher.match(new ParsedItem("1 bhujiya", "bhujiya", 1, "pack", null), catalog);
        assertThat(bhujia.result().matched()).isTrue();
        assertThat(bhujia.result().outOfStock()).isTrue();
        assertThat(bhujia.effectiveQty()).isZero();
    }

    private CatalogProduct product(Long id, String name, String price, int stock, String unit, String cat, String syn) {
        CatalogProduct p = new CatalogProduct();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setUnit(unit);
        p.setCategoryName(cat);
        p.setSynonyms(syn);
        p.setSource("DRC_FALLBACK");
        return p;
    }
}

