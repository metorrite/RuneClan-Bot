package com.runeclans.bot.clansite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builder v3's layout v2 shape checks (T-363). The fixtures under {@code builder-v3/v1} and {@code builder-v3/v2} are layouts the website
 * writes (the {@code bot-*} ones are hand-made here; the others are copied from runeclans {@code packages/builder/tests/fixtures}): each must
 * be accepted and come back unchanged, so the bot never silently drops a field the website saves.
 */
class ClanSiteLayoutRulesV2Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int counter = 200000;

    private static String nextId() {
        return "id" + (counter++);
    }

    // ---------- builders ----------

    private static DataObject resp(Object lg) {
        return DataObject.empty().put("lg", lg);
    }

    private static DataObject widget(String type) {
        return DataObject.empty().put("id", nextId()).put("type", type).put("v", 1).put("settings", DataObject.empty().put("title", "Hello")).put("unmet", "hide");
    }

    private static DataObject cell(int span, DataObject... widgets) {
        DataArray list = DataArray.empty();
        for (DataObject w : widgets) list.add(w);
        return DataObject.empty().put("id", nextId()).put("span", resp(span)).put("padding", resp("none")).put("valign", "start").put("surface", "none").put("widgets", list);
    }

    private static DataObject row(DataObject... cells) {
        DataArray list = DataArray.empty();
        for (DataObject c : cells) list.add(c);
        return DataObject.empty().put("id", nextId()).put("gap", resp("md")).put("align", "stretch").put("height", resp(DataObject.empty().put("mode", "auto"))).put("cells", list);
    }

    private static DataObject section(DataObject... rows) {
        DataArray list = DataArray.empty();
        for (DataObject r : rows) list.add(r);
        return DataObject.empty().put("id", nextId()).put("width", "wide").put("background", DataObject.empty().put("kind", "none"))
                .put("padding", resp(DataObject.empty().put("y", "md").put("x", "none"))).put("height", resp(DataObject.empty().put("mode", "auto"))).put("rows", list);
    }

    private static DataObject page(String title, String path, DataObject... sections) {
        DataArray list = DataArray.empty();
        for (DataObject s : sections) list.add(s);
        return DataObject.empty().put("id", nextId()).put("title", title).put("path", path).put("hidden", false).put("access", "public").put("sections", list);
    }

    private static DataObject header() {
        return DataObject.empty().put("layout", "logo-left").put("logo", DataObject.empty().put("show", true).put("text", "name").put("height", 40))
                .put("nav", DataArray.empty()).put("slots", DataObject.empty().put("left", DataArray.empty()).put("right", DataArray.empty()))
                .put("sticky", false).put("transparent", "never").put("height", "normal").put("mobile", "drawer");
    }

    private static DataObject layout(DataObject... pages) {
        DataArray list = DataArray.empty();
        for (DataObject p : pages) list.add(p);
        return DataObject.empty().put("v", 2).put("look", DataObject.empty().put("theme", "modern").put("mode", "dark").put("accent", "#c9a227"))
                .put("style", DataObject.empty()).put("header", header())
                .put("footer", DataObject.empty().put("columns", DataArray.empty()).put("showSocial", false).put("badge", true)).put("pages", list);
    }

    private static DataObject good() {
        return layout(page("Home", "", section(row(cell(8, widget("heading")), cell(4, widget("roster"))))),
                page("Roster", "roster", section(row(cell(12, widget("roster"))))));
    }

    private static DataObject pageWith(int widgets) {
        DataObject[] cells = new DataObject[0];
        List<DataObject> rows = new ArrayList<>();
        int left = widgets;
        while (left > 0) {
            List<DataObject> rowCells = new ArrayList<>();
            for (int c = 0; c < ClanSiteLayoutRulesV2.MAX_CELLS && left > 0; c++) {
                int n = Math.min(ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_CELL, left);
                DataObject[] ws = new DataObject[n];
                for (int w = 0; w < n; w++) ws[w] = widget("text");
                rowCells.add(cell(2, ws));
                left -= n;
            }
            rows.add(row(rowCells.toArray(cells)));
        }
        List<DataObject> sections = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += ClanSiteLayoutRulesV2.MAX_ROWS) {
            sections.add(section(rows.subList(i, Math.min(rows.size(), i + ClanSiteLayoutRulesV2.MAX_ROWS)).toArray(cells)));
        }
        return page("Big", "big", sections.toArray(cells));
    }

    private static List<String> problems(DataObject layout) {
        return problems(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM);
    }

    private static List<String> problems(DataObject layout, ClanSiteLayoutRulesV2.Limits limits) {
        List<String> problems = new ArrayList<>();
        ClanSiteLayoutRules.clean(layout, limits, problems);
        return problems;
    }

    private static boolean has(List<String> problems, String part) {
        return problems.stream().anyMatch(p -> p.contains(part));
    }

    // ---------- fixtures ----------

    private static List<Path> fixtures(String version) throws Exception {
        URL dir = ClanSiteLayoutRulesV2Test.class.getResource("/builder-v3/" + version);
        assertNotNull(dir, "the builder-v3/" + version + " fixtures are on the test classpath");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyV2FixtureIsAcceptedUnchanged() throws Exception {
        List<Path> files = fixtures("v2");
        assertFalse(files.isEmpty(), "there are v2 fixtures to check");
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            List<String> problems = new ArrayList<>();
            String clean = ClanSiteLayoutRules.clean(DataObject.fromJson(raw), ClanSiteLayoutRulesV2.Limits.PREMIUM, problems);
            if (clean == null) {
                failures.add(file.getFileName() + " refused: " + problems);
                continue;
            }
            if (!JSON.readTree(raw).equals(JSON.readTree(clean))) failures.add(file.getFileName() + " doesn't come back exactly as the website sent it: " + diff(JSON.readTree(raw), JSON.readTree(clean), ""));
            if (!clean.equals(ClanSiteLayoutRules.clean(DataObject.fromJson(clean), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()))) {
                failures.add(file.getFileName() + ": cleaning twice changes something");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** The first place two JSON trees differ, for a readable failure. */
    private static String diff(JsonNode expected, JsonNode actual, String at) {
        if (expected.equals(actual)) return "";
        if (expected.isObject() && actual.isObject()) {
            for (var it = expected.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!actual.has(key)) return at + "." + key + " dropped";
                String inner = diff(expected.get(key), actual.get(key), at + "." + key);
                if (!inner.isEmpty()) return inner;
            }
            for (var it = actual.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!expected.has(key)) return at + "." + key + " added (" + actual.get(key) + ")";
            }
        }
        if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) {
                String inner = diff(expected.get(i), actual.get(i), at + "[" + i + "]");
                if (!inner.isEmpty()) return inner;
            }
        }
        return at + ": " + expected + " became " + actual;
    }

    /** The v1 fixtures that are junk on purpose (an id used twice, broken values): the website's migration copes with them, the v1 rules refuse them. */
    private static final Set<String> V1_JUNK = Set.of("edge-cases.json", "messy.json");
    /**
     * The one {@code v2-invalid} fixture the bot accepts: the website refuses a field the format doesn't have, the bot drops it (as for v1),
     * so a field added on the website before the bot knows it never makes a save fail.
     */
    private static final Set<String> V2_DROPPED_NOT_REFUSED = Set.of("unknown-field.json");

    @Test
    void everyV1FixtureStillSavesWithTheV1Rules() throws Exception {
        List<Path> files = fixtures("v1");
        assertFalse(files.isEmpty(), "there are v1 fixtures to check");
        int checked = 0;
        for (Path file : files) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            List<String> problems = new ArrayList<>();
            String clean = ClanSiteLayoutRules.clean(DataObject.fromJson(raw), ClanSiteLayoutRulesV2.Limits.of(12, 40), problems);
            if (V1_JUNK.contains(file.getFileName().toString())) {
                assertNull(clean, file.getFileName() + " is refused by the v1 rules");
                continue;
            }
            assertNotNull(clean, file.getFileName() + ": " + problems);
            assertEquals(1, DataObject.fromJson(clean).getInt("v"), "a v1 layout stays v1 (the website migrates it, never the bot)");
            checked++;
        }
        assertTrue(checked >= 5, "the starters, the import and the bot's own v1 fixture were checked");
    }

    @Test
    void everyInvalidV2FixtureIsRefused() throws Exception {
        List<Path> files = fixtures("v2-invalid").stream().filter(p -> !p.getFileName().toString().equals("index.json")).toList();
        assertTrue(files.size() >= 30, "the model's invalid fixtures are there");
        JsonNode index = JSON.readTree(Files.readString(files.get(0).resolveSibling("index.json"), StandardCharsets.UTF_8));
        assertEquals(files.size(), index.size(), "index.json lists every invalid fixture");
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            List<String> problems = new ArrayList<>();
            String clean = ClanSiteLayoutRules.clean(DataObject.fromJson(Files.readString(file, StandardCharsets.UTF_8)), ClanSiteLayoutRulesV2.Limits.PREMIUM, problems);
            if (V2_DROPPED_NOT_REFUSED.contains(name)) {
                if (clean == null) failures.add(name + " should be kept (the unknown field dropped): " + problems);
            } else if (clean != null || problems.isEmpty()) {
                failures.add(name + " must be refused");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ---------- shape ----------

    @Test
    void aGoodLayoutIsKeptAndUnknownFieldsDropped() {
        DataObject layout = good();
        layout.put("extra", "dropped");
        layout.getArray("pages").getObject(0).getArray("sections").getObject(0).put("tone", "panel");
        List<String> problems = new ArrayList<>();
        String clean = ClanSiteLayoutRulesV2.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        DataObject out = DataObject.fromJson(clean);
        assertFalse(out.hasKey("extra"));
        assertFalse(out.getArray("pages").getObject(0).getArray("sections").getObject(0).hasKey("tone"), "v1's tone isn't a v2 field");
        assertEquals(2, out.getInt("v"));
    }

    @Test
    void aCellsLeftToRightAlignmentIsKeptAndFullWidthStoredAsNoField() {
        for (String value : List.of("start", "center", "end", "stretch")) {
            DataObject layout = good();
            firstCell(layout).put("halign", value);
            List<String> problems = new ArrayList<>();
            DataObject out = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, problems));
            assertTrue(problems.isEmpty(), value + ": " + problems);
            if (value.equals("stretch")) assertFalse(firstCell(out).hasKey("halign"), "full width is the default, stored as no field");
            else assertEquals(value, firstCell(out).getString("halign"), value);
        }
        DataObject untouched = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(firstCell(untouched).hasKey("halign"), "a layout without it gets none");
        DataObject bad = good();
        firstCell(bad).put("halign", "middle");
        assertTrue(has(problems(bad), "unknown left-to-right alignment"));
    }

    private static DataObject firstCell(DataObject layout) {
        return layout.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0);
    }

    @Test
    void missingOptionalValuesGetTheDesignDefaults() {
        DataObject w = DataObject.empty().put("id", nextId()).put("type", "heading");
        DataObject c = DataObject.empty().put("id", nextId()).put("span", resp(12)).put("widgets", DataArray.empty().add(w));
        DataObject r = DataObject.empty().put("id", nextId()).put("cells", DataArray.empty().add(c));
        DataObject s = DataObject.empty().put("id", nextId()).put("rows", DataArray.empty().add(r));
        DataObject p = DataObject.empty().put("id", nextId()).put("title", "Home").put("path", "").put("sections", DataArray.empty().add(s));
        DataObject layout = DataObject.empty().put("v", 2).put("pages", DataArray.empty().add(p));
        List<String> problems = new ArrayList<>();
        DataObject out = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, problems));
        assertTrue(problems.isEmpty(), problems.toString());
        DataObject page = out.getArray("pages").getObject(0);
        assertEquals("public", page.getString("access"));
        DataObject section = page.getArray("sections").getObject(0);
        assertEquals("wide", section.getString("width"));
        assertEquals("none", section.getObject("background").getString("kind"));
        assertEquals("auto", section.getObject("height").getObject("lg").getString("mode"));
        DataObject widget = section.getArray("rows").getObject(0).getArray("cells").getObject(0).getArray("widgets").getObject(0);
        assertEquals(1, widget.getInt("v"));
        assertEquals("hide", widget.getString("unmet"));
        assertEquals("logo-left", out.getObject("header").getString("layout"));
        assertTrue(out.getObject("footer").getBoolean("badge"));
    }

    @Test
    void treeLimits() {
        DataObject tooManyRows = good();
        DataObject s = tooManyRows.getArray("pages").getObject(0).getArray("sections").getObject(0);
        DataArray rows = s.getArray("rows");
        for (int i = 0; i < ClanSiteLayoutRulesV2.MAX_ROWS; i++) rows.add(row(cell(12)));
        assertTrue(has(problems(tooManyRows), "1 to " + ClanSiteLayoutRulesV2.MAX_ROWS + " rows"));

        DataObject emptySection = layout(page("Home", "", section()));
        assertTrue(has(problems(emptySection), "rows"), "a section needs a row");

        DataObject[] cells = new DataObject[ClanSiteLayoutRulesV2.MAX_CELLS + 1];
        for (int i = 0; i < cells.length; i++) cells[i] = cell(2);
        assertTrue(has(problems(layout(page("Home", "", section(row(cells))))), "1 to " + ClanSiteLayoutRulesV2.MAX_CELLS + " cells"));

        DataObject[] widgets = new DataObject[ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_CELL + 1];
        for (int i = 0; i < widgets.length; i++) widgets[i] = widget("text");
        assertTrue(has(problems(layout(page("Home", "", section(row(cell(12, widgets)))))), "more than " + ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_CELL + " widgets"));

        DataObject[] sections = new DataObject[ClanSiteLayoutRulesV2.MAX_SECTIONS + 1];
        for (int i = 0; i < sections.length; i++) sections[i] = section(row(cell(12)));
        assertTrue(has(problems(layout(page("Home", "", sections))), "more than " + ClanSiteLayoutRulesV2.MAX_SECTIONS + " sections"));

        DataObject flowing = layout(page("Home", "", section(row(cell(8), cell(8), cell(8)))));
        assertTrue(problems(flowing).isEmpty(), "cells flow onto a new line when a row adds up to more than 12");
    }

    @Test
    void spansHeightsAndSpacing() {
        DataObject badSpan = layout(page("Home", "", section(row(cell(13)))));
        assertTrue(has(problems(badSpan), "impossible width"));
        DataObject zeroSpan = layout(page("Home", "", section(row(cell(0)))));
        assertTrue(has(problems(zeroSpan), "impossible width"));

        DataObject tabletSpan = good();
        tabletSpan.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0)
                .put("span", DataObject.empty().put("lg", 8).put("md", 6).put("base", 12).put("xl", 3));
        String clean = ClanSiteLayoutRulesV2.clean(tabletSpan, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>());
        DataObject span = DataObject.fromJson(clean).getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0)
                .getArray("cells").getObject(0).getObject("span");
        assertEquals(6, span.getInt("md"));
        assertFalse(span.hasKey("xl"), "only the three breakpoints are kept");

        DataObject noLg = layout(page("Home", "", section(row(cell(12)))));
        noLg.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0)
                .put("span", DataObject.empty().put("md", 6));
        assertTrue(has(problems(noLg), "impossible width"), "desktop is always set");

        DataObject tall = good();
        tall.getArray("pages").getObject(0).getArray("sections").getObject(0).put("height", resp(DataObject.empty().put("mode", "fixed").put("px", 5000)));
        assertTrue(has(problems(tall), "impossible height"));

        DataObject viewportRow = good();
        viewportRow.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0)
                .put("height", resp(DataObject.empty().put("mode", "viewport").put("pct", 50)));
        assertTrue(has(problems(viewportRow), "row with an impossible height"), "only sections may take a share of the screen");

        DataObject viewportSection = good();
        viewportSection.getArray("pages").getObject(0).getArray("sections").getObject(0).put("height", resp(DataObject.empty().put("mode", "viewport").put("pct", 50)));
        assertTrue(problems(viewportSection).isEmpty());

        DataObject rawPx = good();
        rawPx.getArray("pages").getObject(0).getArray("sections").getObject(0).put("padding", resp(DataObject.empty().put("y", "12px").put("x", "md")));
        assertTrue(has(problems(rawPx), "unknown spacing"), "spacing is tokens, never raw px");
    }

    @Test
    void pagesAddressesTemplatesAndAccess() {
        DataObject reserved = good();
        reserved.getArray("pages").getObject(1).put("path", "admin");
        assertTrue(has(problems(reserved), "needs an address"));

        DataObject twoMembers = layout(page("Home", "", section(row(cell(12)))), page("A", "a").put("template", "member"), page("B", "b").put("template", "member"));
        assertTrue(has(problems(twoMembers), "only one member page"));

        DataObject homeTemplate = layout(page("Home", "").put("template", "event"));
        assertTrue(has(problems(homeTemplate), "home page can't be"));

        DataObject access = good();
        access.getArray("pages").getObject(1).put("access", "admins");
        assertTrue(has(problems(access), "unknown access level"));

        DataObject anchors = layout(page("Home", "", section(row(cell(12))).put("anchor", "top"), section(row(cell(12))).put("anchor", "top")));
        assertTrue(has(problems(anchors), "two sections with the link name #top"));

        DataObject seo = good();
        seo.getArray("pages").getObject(0).put("seo", DataObject.empty().put("image", "http://insecure.example.test/x.png"));
        assertTrue(has(problems(seo), "sharing picture"));

        for (DataObject bad : List.of(DataObject.empty().put("template", "Bad Id").put("v", 1), DataObject.empty().put("template", "members").put("v", 0),
                DataObject.empty().put("template", "members").put("v", 1).put("extra", true), DataObject.empty().put("template", "members").put("v", "1"))) {
            DataObject origin = good();
            origin.getArray("pages").getObject(1).put("origin", bad);
            assertTrue(has(problems(origin), "broken page template note"), bad.toString());
        }

        DataObject duplicateIds = good();
        String id = duplicateIds.getArray("pages").getObject(0).getString("id");
        duplicateIds.getArray("pages").getObject(1).getArray("sections").getObject(0).put("id", id);
        assertTrue(has(problems(duplicateIds), "share the id"));
    }

    @Test
    void pageTemplateOriginIsKept() {
        // F14: a page made from a page template remembers it; saving must not drop it, or "Reset to template" disappears after a reload
        DataObject layout = good();
        layout.getArray("pages").getObject(1).put("origin", DataObject.empty().put("template", "hall-of-fame").put("v", 3));
        List<String> problems = new ArrayList<>();
        DataObject out = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, problems));
        assertTrue(problems.isEmpty(), problems.toString());
        DataObject origin = out.getArray("pages").getObject(1).getObject("origin");
        assertEquals("hall-of-fame", origin.getString("template"));
        assertEquals(3, origin.getInt("v"));
        assertFalse(out.getArray("pages").getObject(0).hasKey("origin"));
    }

    @Test
    void headerNavAndLinks() {
        DataObject nav = good();
        DataArray items = DataArray.empty();
        for (int i = 0; i < 20; i++) {
            DataObject item = DataObject.empty().put("id", nextId()).put("label", "Item " + i).put("link", DataObject.empty().put("kind", "invite"));
            item.put("children", DataArray.empty().add(DataObject.empty().put("id", nextId()).put("label", "Child").put("link", DataObject.empty().put("kind", "invite"))));
            items.add(item);
        }
        nav.getObject("header").put("nav", items);
        assertTrue(problems(nav).isEmpty(), "40 items counting children is the limit");
        items.add(DataObject.empty().put("id", nextId()).put("label", "One more"));
        assertTrue(has(problems(nav), "up to " + ClanSiteLayoutRulesV2.MAX_NAV_ITEMS + " items"));

        DataObject childWithoutLink = good();
        childWithoutLink.getObject("header").put("nav", DataArray.empty().add(DataObject.empty().put("id", nextId()).put("label", "Clan")
                .put("children", DataArray.empty().add(DataObject.empty().put("id", nextId()).put("label", "Roster")))));
        assertTrue(has(problems(childWithoutLink), "needs a link"));

        for (String bad : List.of("javascript:alert(1)", "http://example.test/", "https://user:pw@example.test/", "")) {
            DataObject link = good();
            link.getObject("header").put("nav", DataArray.empty().add(DataObject.empty().put("id", nextId()).put("label", "Out")
                    .put("link", DataObject.empty().put("kind", "url").put("href", bad))));
            assertTrue(has(problems(link), "https address"), bad);
        }
        DataObject unknownKind = good();
        unknownKind.getObject("header").put("nav", DataArray.empty().add(DataObject.empty().put("id", nextId()).put("label", "Out")
                .put("link", DataObject.empty().put("kind", "script"))));
        assertTrue(has(problems(unknownKind), "broken link"));

        DataObject slot = good();
        slot.getObject("header").getObject("slots").put("right", DataArray.empty().add(DataObject.empty().put("kind", "iframe")));
        assertTrue(has(problems(slot), "unknown kind"), "header slots are a closed list (no custom code, ever)");

        DataObject logo = good();
        logo.getObject("header").getObject("logo").put("height", 200);
        assertTrue(has(problems(logo), "24 to 96"));

        DataObject announcement = good();
        announcement.getObject("header").put("announcement", DataObject.empty().put("text", "Hi").put("until", "next week"));
        assertTrue(has(problems(announcement), "isn't a date"));
    }

    @Test
    void themeKitIsKeptOnlyWhenSetAndChecked() {
        // the website's theme kits (runeclans T-575): look.kit survives a save, a site without one gains nothing, an unknown one is refused
        for (String kit : ClanSiteLayoutRules.KITS) {
            DataObject withKit = good();
            withKit.getObject("look").put("kit", kit);
            assertTrue(problems(withKit).isEmpty(), problems(withKit).toString());
            DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(withKit, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
            assertEquals(kit, clean.getObject("look").getString("kit"));
        }

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(plain.getObject("look").hasKey("kit"), "nothing is added to a layout that doesn't set it");

        DataObject cleared = good();
        cleared.getObject("look").putNull("kit");
        assertTrue(problems(cleared).isEmpty());
        assertFalse(DataObject.fromJson(ClanSiteLayoutRulesV2.clean(cleared, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>())).getObject("look").hasKey("kit"));

        DataObject bad = good();
        bad.getObject("look").put("kit", "crystal");
        assertTrue(has(problems(bad), "theme kits"));
    }

    @Test
    void motionStyleIsKeptAndChecked() {
        // the site admin's animation style (runeclans docs/MOTION.md): stored only when set, one of the three styles
        DataObject look = good();
        look.put("style", DataObject.empty().put("motion", "quill"));
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertEquals("quill", clean.getObject("style").getString("motion"));

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(plain.hasKey("style") && plain.getObject("style").hasKey("motion"), "nothing is added to a layout that doesn't set it");

        DataObject bad = good();
        bad.put("style", DataObject.empty().put("motion", "explode"));
        assertTrue(has(problems(bad), "animation style"));
    }

    @Test
    void styleAndFooter() {
        DataObject colours = good();
        colours.put("style", DataObject.empty().put("colours", DataObject.empty().put("accent", "red").put("unknown", "#ffffff")));
        assertTrue(has(problems(colours), "#rrggbb"));

        DataObject dropped = good();
        dropped.put("style", DataObject.empty().put("colours", DataObject.empty().put("unknown", "#ffffff")).put("css", "body{}"));
        String clean = ClanSiteLayoutRulesV2.clean(dropped, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>());
        DataObject style = DataObject.fromJson(clean).getObject("style");
        assertFalse(style.hasKey("css"), "no raw CSS is ever stored");
        assertFalse(style.getObject("colours").hasKey("unknown"));

        DataObject radius = good();
        radius.put("style", DataObject.empty().put("radius", "huge"));
        assertTrue(has(problems(radius), "corner rounding"));

        DataObject footer = good();
        DataArray columns = DataArray.empty();
        for (int i = 0; i <= ClanSiteLayoutRulesV2.MAX_FOOTER_COLUMNS; i++) columns.add(DataObject.empty().put("title", "C").put("links", DataArray.empty()));
        footer.getObject("footer").put("columns", columns);
        assertTrue(has(problems(footer), "up to " + ClanSiteLayoutRulesV2.MAX_FOOTER_COLUMNS + " columns"));
    }

    @Test
    void headerLogoLookLineAndPanelTitlesAreKept() {
        // T-508 (runeclans Younglings gaps): stored only when the website sets them, and kept exactly as sent
        DataObject look = good();
        look.getObject("header").getObject("logo").put("textColour", "accent").put("textCase", "upper").put("shape", "round");
        look.getObject("header").put("accentLine", false);
        look.put("style", DataObject.empty().put("titles", "caps"));
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        DataObject logo = clean.getObject("header").getObject("logo");
        assertEquals("accent", logo.getString("textColour"));
        assertEquals("upper", logo.getString("textCase"));
        assertEquals("round", logo.getString("shape"));
        assertFalse(clean.getObject("header").getBoolean("accentLine"));
        assertEquals("caps", clean.getObject("style").getString("titles"));

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(plain.getObject("header").hasKey("accentLine"), "nothing is added to a layout that doesn't set it");
        assertFalse(plain.getObject("header").getObject("logo").hasKey("textColour"));

        DataObject bad = good();
        bad.getObject("header").getObject("logo").put("textColour", "pink").put("shape", "star");
        bad.getObject("header").put("accentLine", "yes");
        bad.put("style", DataObject.empty().put("titles", "huge"));
        List<String> found = problems(bad);
        assertTrue(has(found, "unknown colour"));
        assertTrue(has(found, "unknown shape"));
        assertTrue(has(found, "coloured line"));
        assertTrue(has(found, "panel title look"));
    }

    @Test
    void younglingsRebuildFields() {
        // runeclans T-518 (1:1 Younglings rebuild): narrower widths, a glowing background, a hidden footer, a centred menu, a plain current item
        DataObject look = good();
        DataObject section = look.getArray("pages").getObject(0).getArray("sections").getObject(0);
        section.put("width", "slim").put("background", DataObject.empty().put("kind", "glow"));
        look.getObject("header").put("navAlign", "center").put("navActive", "text");
        look.getObject("footer").put("hidden", true);
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        DataObject cleanSection = clean.getArray("pages").getObject(0).getArray("sections").getObject(0);
        assertEquals("slim", cleanSection.getString("width"));
        assertEquals("glow", cleanSection.getObject("background").getString("kind"));
        assertEquals("center", clean.getObject("header").getString("navAlign"));
        assertEquals("text", clean.getObject("header").getString("navActive"));
        assertTrue(clean.getObject("footer").getBoolean("hidden"));

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(plain.getObject("header").hasKey("navAlign"), "nothing is added to a layout that doesn't set it");
        assertFalse(plain.getObject("header").hasKey("navActive"));
        assertFalse(plain.getObject("footer").hasKey("hidden"));

        DataObject bad = good();
        bad.getArray("pages").getObject(0).getArray("sections").getObject(0).put("width", "tiny");
        bad.getObject("header").put("navAlign", "left").put("navActive", "blink");
        bad.getObject("footer").put("hidden", "yes");
        List<String> found = problems(bad);
        assertTrue(has(found, "unknown width"));
        assertTrue(has(found, "unknown position"));
        assertTrue(has(found, "current page"));
        assertTrue(has(found, "hide setting"));
    }

    @Test
    void phoneMenuRow() {
        // runeclans T-518: the header's links in a scrolling row on phones
        DataObject look = good();
        look.getObject("header").put("mobile", "row");
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertEquals("row", clean.getObject("header").getString("mobile"));

        DataObject bad = good();
        bad.getObject("header").put("mobile", "carousel");
        assertTrue(has(problems(bad), "phone menu"));
    }

    @Test
    void sectionSpaceBelow() {
        // T-518: a section's own space under its content
        DataObject look = good();
        look.getArray("pages").getObject(0).getArray("sections").getObject(0)
                .put("padding", DataObject.empty().put("lg", DataObject.empty().put("y", "md").put("x", "none").put("bottom", "none")));
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        DataObject padding = clean.getArray("pages").getObject(0).getArray("sections").getObject(0).getObject("padding").getObject("lg");
        assertEquals("none", padding.getString("bottom"));
        assertEquals("md", padding.getString("y"));

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(plain.getArray("pages").getObject(0).getArray("sections").getObject(0).getObject("padding").getObject("lg").hasKey("bottom"));

        DataObject bad = good();
        bad.getArray("pages").getObject(0).getArray("sections").getObject(0)
                .put("padding", DataObject.empty().put("lg", DataObject.empty().put("y", "md").put("x", "none").put("bottom", "huge")));
        assertTrue(has(problems(bad), "section with unknown spacing"));
    }

    @Test
    void sectionRowSpacing() {
        // T-518: a section's own space between its rows
        DataObject look = good();
        look.getArray("pages").getObject(0).getArray("sections").getObject(0).put("gap", DataObject.empty().put("lg", "ml").put("base", "sm"));
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        DataObject section = clean.getArray("pages").getObject(0).getArray("sections").getObject(0);
        assertEquals("ml", section.getObject("gap").getString("lg"));
        assertEquals("sm", section.getObject("gap").getString("base"));

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        assertFalse(plain.getArray("pages").getObject(0).getArray("sections").getObject(0).hasKey("gap"), "nothing is added to a layout that doesn't set it");

        DataObject bad = good();
        bad.getArray("pages").getObject(0).getArray("sections").getObject(0).put("gap", DataObject.empty().put("lg", "huge"));
        assertTrue(has(problems(bad), "between its rows"));
    }

    @Test
    void spacingFields() {
        // T-518: a 1.5rem spacing step, a cell's own space between widgets, and narrow side margins
        DataObject look = good();
        DataObject cell = look.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0);
        cell.put("gap", DataObject.empty().put("lg", "ml").put("base", "sm"));
        cell.put("padding", DataObject.empty().put("lg", "ml"));
        look.put("style", DataObject.empty().put("gutter", "narrow").put("tabs", "soft"));
        assertTrue(problems(look).isEmpty(), problems(look).toString());
        DataObject clean = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(look, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        DataObject cleanCell = clean.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0);
        assertEquals("ml", cleanCell.getObject("gap").getString("lg"));
        assertEquals("sm", cleanCell.getObject("gap").getString("base"));
        assertEquals("ml", cleanCell.getObject("padding").getString("lg"));
        assertEquals("narrow", clean.getObject("style").getString("gutter"));
        assertEquals("soft", clean.getObject("style").getString("tabs"));

        DataObject plain = DataObject.fromJson(ClanSiteLayoutRulesV2.clean(good(), ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
        DataObject plainCell = plain.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0);
        assertFalse(plainCell.hasKey("gap"), "nothing is added to a layout that doesn't set it");

        DataObject bad = good();
        bad.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0)
                .put("gap", DataObject.empty().put("lg", "huge"));
        bad.put("style", DataObject.empty().put("gutter", "wide").put("tabs", "round"));
        List<String> found = problems(bad);
        assertTrue(has(found, "between its widgets"));
        assertTrue(has(found, "side margins"));
        assertTrue(has(found, "time-frame switches"));
    }

    @Test
    void sizes() {
        DataObject bigWidget = good();
        bigWidget.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0)
                .getArray("widgets").getObject(0).put("settings", DataObject.empty().put("markdown", "x".repeat(ClanSiteLayoutRulesV2.MAX_SETTINGS_BYTES + 1)));
        assertTrue(has(problems(bigWidget), "too much in it"));

        DataObject fitsV2 = good();
        fitsV2.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0)
                .getArray("widgets").getObject(0).put("settings", DataObject.empty().put("markdown", "x".repeat(6000)));
        assertTrue(problems(fitsV2).isEmpty(), "v2 allows 8 KB of settings (v1: 4 KB)");

        DataObject huge = good().put("pad", "x".repeat(ClanSiteLayoutRulesV2.MAX_BYTES));
        assertTrue(has(problems(huge), "too big"));
        DataObject biggerThanV1 = good().put("pad", "x".repeat(100 * 1024));
        assertTrue(problems(biggerThanV1).isEmpty(), "v2 allows 256 KB (v1: 64 KB)");
    }

    // ---------- plan limits ----------

    @Test
    void pageAndWidgetCountsFollowThePlan() {
        ClanSiteLayoutRulesV2.Limits free = ClanSiteLayoutRulesV2.Limits.of(12, 40);
        DataObject[] pages = new DataObject[13];
        pages[0] = page("Home", "");
        for (int i = 1; i < pages.length; i++) pages[i] = page("P" + i, "p" + i);
        assertTrue(has(problems(layout(pages), free), "Your plan allows up to 12 pages"));
        assertTrue(problems(layout(pages)).isEmpty(), "premium allows 24");

        DataObject[] tooMany = new DataObject[ClanSiteLayoutRulesV2.MAX_PAGES + 1];
        tooMany[0] = page("Home", "");
        for (int i = 1; i < tooMany.length; i++) tooMany[i] = page("P" + i, "p" + i);
        assertTrue(has(problems(layout(tooMany)), "up to " + ClanSiteLayoutRulesV2.MAX_PAGES + " pages"));

        DataObject busy = layout(page("Home", ""), pageWith(41));
        assertTrue(has(problems(busy, free), "your plan allows 40"));
        assertTrue(problems(busy).isEmpty());
        DataObject full = layout(page("Home", ""), pageWith(ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_PAGE + 1));
        assertTrue(has(problems(full), "a page can hold " + ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_PAGE));
    }

    @Test
    void aSmallerPlanKeepsWhatTheSiteAlreadyHas() {
        ClanSiteLayoutRulesV2.Limits free = ClanSiteLayoutRulesV2.Limits.of(12, 40);
        DataObject[] pages = new DataObject[14];
        pages[0] = page("Home", "");
        pages[1] = pageWith(50);
        for (int i = 2; i < pages.length; i++) pages[i] = page("P" + i, "p" + i);
        DataObject stored = layout(pages);
        String storedJson = ClanSiteLayoutRulesV2.clean(stored, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>());
        assertNotNull(storedJson);

        ClanSiteLayoutRulesV2.Limits kept = free.keeping(storedJson);
        assertTrue(problems(stored, kept).isEmpty(), "saving the site as it is still works after the plan ended");

        DataObject morePages = DataObject.fromJson(storedJson);
        morePages.getArray("pages").add(page("New", "new"));
        assertTrue(has(problems(morePages, kept), "keeps the 14 it has, but can't add more"), "but no new page");

        DataObject moreOnBusyPage = DataObject.fromJson(storedJson);
        moreOnBusyPage.getArray("pages").getObject(1).getArray("sections").getObject(0).getArray("rows").add(row(cell(12, widget("text"))));
        assertTrue(has(problems(moreOnBusyPage, kept), "keeps the 50 it had"), "nor a new widget on the page that is over the limit");

        DataObject moreWidgets = DataObject.fromJson(storedJson);
        moreWidgets.getArray("pages").getObject(1).getArray("sections").getObject(0).getArray("rows").getObject(0).put("cells",
                DataArray.empty().add(cell(12, widget("text"))));
        assertTrue(problems(moreWidgets, kept).isEmpty(), "fewer widgets is fine");

        DataObject otherPageGrows = DataObject.fromJson(storedJson);
        otherPageGrows.getArray("pages").remove(2).insert(2, pageWith(41).put("path", "p2"));
        assertTrue(has(problems(otherPageGrows, kept), "your plan allows 40"), "another page can't grow past the free limit");

        assertEquals(free, free.keeping(null));
        assertEquals(free, free.keeping("not json"));
    }

    @Test
    void countWidgetsReadsBothVersions() {
        DataObject v1Page = DataObject.fromJson("{\"id\":\"p1\",\"sections\":[{\"columns\":[{\"widgets\":[{\"id\":\"a\",\"settings\":{\"widgets\":[{},{}]}},{\"id\":\"b\"}]}]}]}");
        assertEquals(2, ClanSiteLayoutRulesV2.countWidgets(v1Page), "a widget's own settings are never counted");
        DataObject v2Page = pageWith(17);
        assertEquals(17, ClanSiteLayoutRulesV2.countWidgets(v2Page));
    }

    @Test
    void v1StillUsesTheV1Rules() {
        DataObject v1 = DataObject.fromJson("{\"v\":1,\"pages\":[{\"id\":\"page01\",\"title\":\"Home\",\"path\":\"\",\"sections\":[]}]}");
        List<String> problems = new ArrayList<>();
        assertNotNull(ClanSiteLayoutRules.clean(v1, ClanSiteLayoutRulesV2.Limits.of(12, 40), problems), problems.toString());
        DataObject v3 = good().put("v", 3);
        assertTrue(has(problems(v3), "another version"));
        assertNull(ClanSiteLayoutRules.clean(null, ClanSiteLayoutRulesV2.Limits.PREMIUM, new ArrayList<>()));
    }
    // ---------- site type (plan C-9) ----------

    @Test
    void clanOnlyListMatchesTheWebsite() throws Exception {
        URL file = ClanSiteLayoutRulesV2Test.class.getResource("/builder-v3/kinds/clan-only.json");
        assertNotNull(file, "the builder-v3/kinds/clan-only.json fixture is on the test classpath");
        DataArray list = DataArray.fromJson(Files.readString(Path.of(file.toURI()), StandardCharsets.UTF_8));
        Set<String> website = new java.util.HashSet<>();
        for (int i = 0; i < list.length(); i++) website.add(list.getString(i));
        assertEquals(website, ClanSiteLayoutRulesV2.CLAN_ONLY_WIDGETS, "copy packages/builder/tests/fixtures/kinds/clan-only.json and update CLAN_ONLY_WIDGETS");
    }

    @Test
    void communitySitesRefuseClanOnlyWidgets() throws Exception {
        Path fullFeatures = fixtures("v2").stream().filter(p -> p.getFileName().toString().equals("full-features.json")).findFirst().orElseThrow();
        DataObject layout = DataObject.fromJson(Files.readString(fullFeatures, StandardCharsets.UTF_8));
        // a clan website, or one with no type (every site before plan C-9), takes the roster
        assertNotNull(ClanSiteLayoutRules.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, "clan", new ArrayList<>()));
        assertNotNull(ClanSiteLayoutRules.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, null, new ArrayList<>()));
        List<String> problems = new ArrayList<>();
        assertNull(ClanSiteLayoutRules.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, "community", problems));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).endsWith("has a clan-only widget. Community websites have no clan roster or stats: remove it."), problems.get(0));
        // without its clan-only widgets the same layout is a good community layout, stored as for a clan
        List<String> removed = new ArrayList<>();
        DataObject community = ClanSiteLayoutRulesV2.withoutClanOnly(layout, removed);
        assertEquals(1, removed.size(), removed.toString());
        assertNull(ClanSiteLayoutRules.clean(layout, ClanSiteLayoutRulesV2.Limits.PREMIUM, "community", new ArrayList<>()), "the original still holds it");
        String clean = ClanSiteLayoutRules.clean(community, ClanSiteLayoutRulesV2.Limits.PREMIUM, "community", new ArrayList<>());
        assertNotNull(clean);
        assertEquals(ClanSiteLayoutRules.clean(community, ClanSiteLayoutRulesV2.Limits.PREMIUM, "clan", new ArrayList<>()), clean);
        // the shape is still checked first: a broken community layout lists both kinds of problem
        List<String> both = new ArrayList<>();
        assertNull(ClanSiteLayoutRules.clean(DataObject.fromJson(layout.toString()).put("pages", DataArray.empty()), ClanSiteLayoutRulesV2.Limits.PREMIUM, "community", both));
        assertTrue(has(both, "at least its Home page"), both.toString());
    }

    /** Plan C-13: {@code withoutClanOnly} takes out only clan-only widgets, names each, and leaves the original and everything else alone. */
    @Test
    void withoutClanOnlyTakesOutOnlyClanWidgets() {
        String json = "{\"v\":" + ClanSiteLayoutRulesV2.VERSION + ",\"pages\":["
                + "{\"title\":\"Home\",\"sections\":[{\"rows\":[{\"cells\":[{\"widgets\":[{\"type\":\"roster\"},{\"type\":\"text\",\"id\":\"t1\"},\"odd\"]}]}]}]},"
                + "{\"title\":\" \",\"sections\":[{\"rows\":[{\"cells\":[{\"widgets\":[{\"type\":\"clan-stats\"}]},{\"widgets\":[]}]}]}]},"
                + "\"not a page\"]}";
        DataObject layout = DataObject.fromJson(json);
        String before = layout.toString();
        List<String> removed = new ArrayList<>();
        DataObject out = ClanSiteLayoutRulesV2.withoutClanOnly(layout, removed);
        assertEquals(List.of("Home: roster", "A page: clan-stats"), removed);
        assertEquals(before, layout.toString(), "the original is left as it was");
        DataArray home = out.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells").getObject(0).getArray("widgets");
        assertEquals(2, home.length(), "the other widgets stay, the odd entry too");
        assertEquals("t1", home.getObject(0).getString("id"));
        assertEquals("odd", home.getString(1));
        DataArray second = out.getArray("pages").getObject(1).getArray("sections").getObject(0).getArray("rows").getObject(0).getArray("cells");
        assertEquals(2, second.length(), "the cells stay");
        assertEquals(0, second.getObject(0).getArray("widgets").length());
        assertEquals("not a page", out.getArray("pages").getString(2));

        // not v2, or nothing: returned as it is; a v2 layout without pages comes back as a copy
        DataObject v1 = DataObject.fromJson("{\"v\":1,\"pages\":[]}");
        assertSame(v1, ClanSiteLayoutRulesV2.withoutClanOnly(v1, removed));
        assertNull(ClanSiteLayoutRulesV2.withoutClanOnly(null, removed));
        DataObject noPages = DataObject.fromJson("{\"v\":" + ClanSiteLayoutRulesV2.VERSION + "}");
        assertEquals(noPages.toString(), ClanSiteLayoutRulesV2.withoutClanOnly(noPages, removed).toString());
        assertEquals(2, removed.size(), "nothing more was taken out");
    }
}
