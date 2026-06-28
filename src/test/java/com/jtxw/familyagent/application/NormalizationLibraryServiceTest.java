package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.AddNormalizationRuleKeywordCommand;
import com.jtxw.familyagent.application.command.CreateNormalizationRuleCommand;
import com.jtxw.familyagent.application.command.DisableNormalizationRuleKeywordCommand;
import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.application.command.UpdateNormalizationRuleCommand;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationLibraryOperationResult;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.SqliteProductRuleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:48:04
 * @Description: 归一化规则库应用服务测试，覆盖规则基础维护、关键词软禁用和 ProductRuleProvider 实时读取行为
 */
class NormalizationLibraryServiceTest {
    /**
     * 被测归一化规则库应用服务。
     */
    private NormalizationLibraryService service;
    /**
     * 归一化规则仓储，用于构造 SQLite Provider。
     */
    private NormalizationRuleRepository repository;
    /**
     * 商品规则匹配器，用于验证写操作后运行期规则实时生效。
     */
    private ProductRuleMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of("target", "normalization-library-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("normalization-library.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        repository = new NormalizationRuleRepository(jdbcTemplate);
        service = new NormalizationLibraryService(databaseInitializer, repository);
        matcher = new ProductRuleMatcher(new SqliteProductRuleProvider(repository));
    }

    @Test
    void shouldDispatchUnifiedOperationAction() {
        NormalizationLibraryOperationResult result = (NormalizationLibraryOperationResult) service.operate(new NormalizationLibraryOperationCommand(
                "create_rule",
                "body_wash",
                "沐浴露",
                "个护清洁",
                "L",
                "volume",
                80,
                true,
                null,
                null,
                List.of("沐浴露"),
                List.of("沐浴露瓶")
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("create_rule");
        assertThat(result.ruleCode()).isEqualTo("body_wash");
        assertThat(result.normalizedName()).isEqualTo("沐浴露");
        assertThat(matcher.match("舒肤佳沐浴露 720ml").normalizedName()).isEqualTo("沐浴露");
        assertThat(matcher.match("沐浴露瓶旅行装").matched()).isFalse();
    }

    @Test
    void shouldRejectUnsupportedUnifiedOperationAction() {
        assertThatThrownBy(() -> service.operate(new NormalizationLibraryOperationCommand(
                "delete_rule",
                "body_wash",
                "沐浴露",
                "个护清洁",
                "L",
                "volume",
                80,
                true,
                null,
                null,
                List.of(),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的归一化规则库操作");
    }

    @Test
    void shouldCreateRuleAndExposeItToLibraryProviderAndMatcher() {
        service.createRule(new CreateNormalizationRuleCommand(
                "body_wash",
                "沐浴露",
                "个护清洁",
                "L",
                "volume",
                80,
                List.of("沐浴露", "沐浴乳"),
                List.of("沐浴露瓶", "分装瓶")
        ));

        NormalizationLibraryItem item = libraryItem("body_wash");
        assertThat(item.normalizedName()).isEqualTo("沐浴露");
        assertThat(item.category()).isEqualTo("个护清洁");
        assertThat(item.standardUnit()).isEqualTo("L");
        assertThat(item.unitFamily()).isEqualTo("volume");
        assertThat(item.keywords()).contains("沐浴露", "沐浴乳");
        assertThat(item.excludeKeywords()).contains("沐浴露瓶", "分装瓶");
        assertThat(item.enabled()).isTrue();
        assertThat(item.source()).isEqualTo("manual");

        ProductRule rule = providerRule("body_wash");
        assertThat(rule.normalizedName()).isEqualTo("沐浴露");
        assertThat(rule.unitFamily()).isEqualTo(UnitFamily.VOLUME);
        assertThat(matcher.match("舒肤佳沐浴露 720ml").normalizedName()).isEqualTo("沐浴露");
    }

    @Test
    void createRuleShouldAllowOpenCountStandardUnits() {
        List<String> countUnits = List.of("粒", "瓶", "台", "辆");
        for (int index = 0; index < countUnits.size(); index++) {
            String unit = countUnits.get(index);
            String ruleCode = "count_unit_rule_" + index;
            service.createRule(new CreateNormalizationRuleCommand(
                    ruleCode,
                    "自然计数商品" + index,
                    "测试品类",
                    unit,
                    "count",
                    80,
                    List.of("自然计数商品" + index),
                    List.of()
            ));

            NormalizationLibraryItem item = libraryItem(ruleCode);
            assertThat(item.standardUnit()).isEqualTo(unit);
            assertThat(item.unitFamily()).isEqualTo("count");
        }
    }

    @Test
    void createRuleShouldAppendNormalizedNameWhenIncludeKeywordsOmitIt() {
        service.createRule(new CreateNormalizationRuleCommand(
                "auto_keyword_rule",
                "body wash",
                "personal care",
                "L",
                "volume",
                80,
                List.of("shower gel"),
                List.of("travel bottle")
        ));

        NormalizationLibraryItem item = libraryItem("auto_keyword_rule");
        assertThat(item.keywords()).containsExactly("shower gel", "body wash");
        assertThat(providerRule("auto_keyword_rule").includeKeywords()).containsExactly("shower gel", "body wash");
    }

    @Test
    void createRuleShouldNotDuplicateNormalizedNameWhenIncludeKeywordsContainIt() {
        service.createRule(new CreateNormalizationRuleCommand(
                "dedupe_keyword_rule",
                "body wash",
                "personal care",
                "L",
                "volume",
                80,
                List.of("body wash", "body wash"),
                List.of()
        ));

        NormalizationLibraryItem item = libraryItem("dedupe_keyword_rule");
        assertThat(item.keywords().stream().filter("body wash"::equals).count()).isEqualTo(1);
        assertThat(providerRule("dedupe_keyword_rule").includeKeywords()).containsExactly("body wash");
    }

    @Test
    void createRuleShouldRejectNormalizedNameInExcludeKeywords() {
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "exclude_normalized_name_rule",
                "body wash",
                "personal care",
                "L",
                "volume",
                80,
                List.of("shower gel"),
                List.of("body wash")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("include")
                .hasMessageContaining("exclude");
    }

    @Test
    void shouldUpdateRuleBaseFieldsWithoutReplacingKeywords() {
        service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_litter",
                "猫砂",
                "宠物清洁",
                "g",
                "weight",
                120,
                true
        ));

        NormalizationLibraryItem item = libraryItem("cat_litter");
        assertThat(item.category()).isEqualTo("宠物清洁");
        assertThat(item.standardUnit()).isEqualTo("g");
        assertThat(item.unitFamily()).isEqualTo("weight");
        assertThat(item.priority()).isEqualTo(120);
        assertThat(item.keywords()).contains("猫砂");

        ProductRule rule = providerRule("cat_litter");
        assertThat(rule.standardUnit()).isEqualTo("g");
        assertThat(rule.priority()).isEqualTo(120);
    }

    @Test
    void updateRuleShouldAddExcludeKeywordFromSnapshot() {
        service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_food",
                "猫粮",
                "宠物用品",
                "kg",
                "weight",
                90,
                true,
                null,
                List.of("猫粮勺", "储粮桶", "冻干")
        ));

        assertThat(libraryItem("cat_food").excludeKeywords()).contains("猫粮勺", "储粮桶", "冻干");
    }

    @Test
    void updateRuleShouldDisableRemovedExcludeKeywordFromSnapshot() {
        service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_food", "猫粮", "宠物用品", "kg", "weight", 90, true,
                null, List.of("猫粮勺", "储粮桶", "冻干")));

        service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_food", "猫粮", "宠物用品", "kg", "weight", 90, true,
                null, List.of("猫粮勺", "储粮桶")));

        assertThat(libraryItem("cat_food").excludeKeywords()).contains("猫粮勺", "储粮桶");
        assertThat(libraryItem("cat_food").excludeKeywords()).doesNotContain("冻干");
    }

    @Test
    void updateRuleShouldAddIncludeKeywordFromSnapshot() {
        service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_food",
                "猫粮",
                "宠物用品",
                "kg",
                "weight",
                90,
                true,
                List.of("猫粮", "主粮"),
                null
        ));

        assertThat(libraryItem("cat_food").keywords()).contains("猫粮", "主粮");
        assertThat(providerRule("cat_food").includeKeywords()).contains("主粮");
    }

    @Test
    void updateRuleShouldRejectIncludeAndExcludeKeywordConflict() {
        assertThatThrownBy(() -> service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_food",
                "猫粮",
                "宠物用品",
                "kg",
                "weight",
                90,
                true,
                List.of("冻干"),
                List.of("冻干")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能同时作为 include 和 exclude");
    }

    @Test
    void updateRuleShouldClearExcludeKeywordsWhenSnapshotIsEmpty() {
        service.updateRule(new UpdateNormalizationRuleCommand(
                "cat_food",
                "猫粮",
                "宠物用品",
                "kg",
                "weight",
                90,
                true,
                null,
                List.of()
        ));

        assertThat(libraryItem("cat_food").excludeKeywords()).isEmpty();
    }

    @Test
    void updateRuleShouldAllowOpenCountStandardUnits() {
        service.createRule(new CreateNormalizationRuleCommand(
                "coffee_nestle", "雀巢丝滑拿铁", "饮料", "件", "count", 80,
                List.of("雀巢丝滑拿铁"), List.of()));
        service.createRule(new CreateNormalizationRuleCommand(
                "dry_cell", "干电池", "电子", "个", "count", 80,
                List.of("干电池"), List.of("充电宝")));

        service.updateRule(new UpdateNormalizationRuleCommand(
                "coffee_nestle", "雀巢丝滑拿铁", "饮料", "瓶", "count", 80, true));
        service.updateRule(new UpdateNormalizationRuleCommand(
                "dry_cell", "干电池", "电子", "粒", "count", 80, true));

        assertThat(libraryItem("coffee_nestle").standardUnit()).isEqualTo("瓶");
        assertThat(libraryItem("coffee_nestle").unitFamily()).isEqualTo("count");
        assertThat(libraryItem("dry_cell").standardUnit()).isEqualTo("粒");
        assertThat(libraryItem("dry_cell").unitFamily()).isEqualTo("count");
    }

    @Test
    void shouldAddAndDisableIncludeKeyword() {
        service.addKeyword(new AddNormalizationRuleKeywordCommand(
                "laundry_detergent", "衣物清洁液", "include", 100));

        assertThat(matcher.match("衣物清洁液 2L").normalizedName()).isEqualTo("洗衣液");
        assertThat(providerRule("laundry_detergent").includeKeywords()).contains("衣物清洁液");
        assertThat(libraryItem("laundry_detergent").keywords()).contains("衣物清洁液");

        service.disableKeyword(new DisableNormalizationRuleKeywordCommand(
                "laundry_detergent", "衣物清洁液", "include"));

        assertThat(providerRule("laundry_detergent").includeKeywords()).doesNotContain("衣物清洁液");
        assertThat(libraryItem("laundry_detergent").keywords()).doesNotContain("衣物清洁液");
        assertThat(matcher.match("衣物清洁液 2L").matched()).isFalse();
    }

    @Test
    void shouldAddAndDisableExcludeKeyword() {
        service.addKeyword(new AddNormalizationRuleKeywordCommand(
                "cat_litter", "猫砂垫", "exclude", 100));

        assertThat(providerRule("cat_litter").excludeKeywords()).contains("猫砂垫");
        assertThat(libraryItem("cat_litter").excludeKeywords()).contains("猫砂垫");
        assertThat(matcher.match("猫砂垫大号").matched()).isFalse();

        service.disableKeyword(new DisableNormalizationRuleKeywordCommand(
                "cat_litter", "猫砂垫", "exclude"));

        assertThat(providerRule("cat_litter").excludeKeywords()).doesNotContain("猫砂垫");
        assertThat(libraryItem("cat_litter").excludeKeywords()).doesNotContain("猫砂垫");
        assertThat(matcher.match("猫砂垫大号").normalizedName()).isEqualTo("猫砂");
    }

    @Test
    void shouldDisableRuleWithoutPhysicalDelete() {
        service.disableRule("laundry_detergent");

        NormalizationLibraryItem item = libraryItem("laundry_detergent");
        assertThat(item.enabled()).isFalse();
        assertThat(item.keywords()).contains("洗衣液");
        assertThat(repository.listEnabledProductRules())
                .extracting(ProductRule::id)
                .doesNotContain("laundry_detergent");
        assertThat(matcher.match("洗衣液 2L").matched()).isFalse();
    }

    @Test
    void shouldRejectInvalidRuleAndKeywordMutations() {
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "cat_litter", "新猫砂", "宠物用品", "kg", "weight", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归一化规则编码已存在");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "new_cat_litter", "猫砂", "宠物用品", "kg", "weight", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归一化商品名称已存在");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_family", "非法单位族", "测试", "L", "liquid", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法 unitFamily");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_unit", "非法单位", "测试", "kg", "volume", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standardUnit 与 unitFamily 不兼容");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_weight_unit", "重量非法单位", "测试", "粒", "weight", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standardUnit 与 unitFamily 不兼容");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_volume_unit", "体积非法单位", "测试", "瓶", "volume", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standardUnit 与 unitFamily 不兼容");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_draw_count_unit", "抽数非法单位", "测试", "粒", "draw_count", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standardUnit 与 unitFamily 不兼容");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_count_blank_unit", "计数空单位", "测试", " ", "count", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standardUnit 不能为空");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_count_separator_unit", "计数复合单位", "测试", "瓶/盒", "count", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COUNT standardUnit 不能包含分隔符");
        assertThatThrownBy(() -> service.createRule(new CreateNormalizationRuleCommand(
                "bad_count_long_unit", "计数长单位", "测试", "很长很长很长很长的单位名称", "count", 100, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COUNT standardUnit 过长");
        assertThatThrownBy(() -> service.addKeyword(new AddNormalizationRuleKeywordCommand(
                "cat_litter", "猫砂", "positive", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchType 只允许 include 或 exclude");
        assertThatThrownBy(() -> service.addKeyword(new AddNormalizationRuleKeywordCommand(
                "cat_litter", " ", "include", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyword 不能为空");
        assertThatThrownBy(() -> service.addKeyword(new AddNormalizationRuleKeywordCommand(
                "cat_litter", "猫砂盆", "include", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能同时作为 include 和 exclude");
        assertThatThrownBy(() -> service.addKeyword(new AddNormalizationRuleKeywordCommand(
                "missing_rule", "沐浴露", "include", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归一化规则不存在");
    }

    private NormalizationLibraryItem libraryItem(String ruleCode) {
        return service.listLibraryItems().stream()
                .filter(item -> ruleCode.equals(item.ruleCode()))
                .findFirst()
                .orElseThrow();
    }

    private ProductRule providerRule(String ruleCode) {
        return repository.listEnabledProductRules().stream()
                .filter(rule -> ruleCode.equals(rule.id()))
                .findFirst()
                .orElseThrow();
    }
}
