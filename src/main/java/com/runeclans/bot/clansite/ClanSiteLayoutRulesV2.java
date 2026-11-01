package com.runeclans.bot.clansite;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.runeclans.bot.clansite.ClanSiteLayoutRules.ID;
import static com.runeclans.bot.clansite.ClanSiteLayoutRules.MAX_URL;
import static com.runeclans.bot.clansite.ClanSiteLayoutRules.PATH;
import static com.runeclans.bot.clansite.ClanSiteLayoutRules.TYPE;
import static com.runeclans.bot.clansite.ClanSiteLayoutRules.bytes;
import static com.runeclans.bot.clansite.ClanSiteLayoutRules.id;
import static com.runeclans.bot.clansite.ClanSiteLayoutRules.picture;

/**
 * The shape of a builder v3 layout, "layout v2" (T-363, docs/BUILDER-V3.md in the runeclans repo, sections 3, 7 and 9). As for v1, the bot
 * checks only the shape and the sizes and rebuilds the stored copy from the known fields; what a widget's settings mean stays the website's
 * business. The tree is site → page → section → row → cell → widget, plus the header, footer and style overrides. Hard limits are the
 * constants below (section 3.6); the page and widget counts also depend on the server's plan ({@link Limits}). A missing optional value gets
 * the design's default; a value that is there but wrong is a problem.
 */
public final class ClanSiteLayoutRulesV2 {
    private ClanSiteLayoutRulesV2() {}

    public static final int VERSION = 2;
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_PAGES = 24;
    public static final int MAX_SECTIONS = 30;
    public static final int MAX_ROWS = 6;
    public static final int MAX_CELLS = 6;
    public static final int MAX_WIDGETS_PER_CELL = 8;
    public static final int MAX_WIDGETS_PER_PAGE = 80;
    public static final int MAX_SETTINGS_BYTES = 8 * 1024;
    public static final int MAX_NAV_ITEMS = 40;
    public static final int MAX_SLOTS = 6;
    public static final int MAX_SLOT_SETTINGS_BYTES = 2 * 1024;
    public static final int MAX_FOOTER_COLUMNS = 4;
    public static final int MAX_FOOTER_LINKS = 8;
    public static final int MAX_TITLE = ClanSiteLayoutRules.MAX_TITLE;
    public static final int GRID = ClanSiteLayoutRules.GRID;

    /** Phone, tablet and desktop: the breakpoints a {@code Responsive} value and a {@code hide} map may name (section 3.2). */
    public enum Bp {
        base, md, lg
    }

    public static final Set<String> SPACE = Set.of("none", "xs", "sm", "md", "ml", "lg", "xl");
    public static final Set<String> WIDTHS = Set.of("full", "wide", "content", "medium", "narrow", "slim");
    public static final Set<String> BACKGROUNDS = Set.of("none", "surface", "accent", "image", "glow");
    public static final Set<String> ALIGN = Set.of("start", "center", "end", "stretch");
    public static final Set<String> SURFACES = Set.of("none", "panel");
    public static final Set<String> ACCESS = Set.of("public", "members", "staff");
    public static final Set<String> TEMPLATES = Set.of("member", "event", "boss", "item", "account");
    public static final Set<String> HEADER_LAYOUTS = Set.of("logo-left", "logo-center", "logo-right", "stacked");
    public static final Set<String> LOGO_TEXT = Set.of("name", "none", "custom");
    public static final Set<String> SLOT_KINDS = Set.of("search", "bell", "profile", "theme", "discord", "citadel-timer", "online-count", "button");
    public static final Set<String> TRANSPARENT = Set.of("never", "home", "always");
    public static final Set<String> HEADER_HEIGHTS = Set.of("compact", "normal", "tall");
    // "row": every link in a row under the bar that scrolls sideways (runeclans T-518)
    public static final Set<String> MOBILE_NAV = Set.of("drawer", "dropdown", "row");
    public static final Set<String> NAV_ALIGN = Set.of("end", "center");
    public static final Set<String> NAV_ACTIVE = Set.of("pill", "text");
    public static final Set<String> TONES = Set.of("info", "accent");
    public static final Set<String> COLOURS = Set.of("accent", "accent2", "bg", "surface", "text", "muted", "border");
    public static final Set<String> RADIUS = Set.of("none", "sm", "md", "lg", "xl");
    public static final Set<String> DENSITY = Set.of("compact", "normal", "airy");
    public static final Set<String> GLOW = Set.of("off", "soft");
    public static final Set<String> BUTTONS = Set.of("solid", "outline", "soft");
    public static final Set<String> TITLES = Set.of("theme", "caps");
    public static final Set<String> GUTTERS = Set.of("normal", "narrow");
    public static final Set<String> TABS = Set.of("filled", "soft");
    /** The site's motion style (runeclans docs/MOTION.md): missing follows the theme. */
    public static final Set<String> MOTION = Set.of("forge", "quill", "crisp");
    public static final Set<String> LOGO_TEXT_COLOURS = Set.of("text", "accent");
    public static final Set<String> LOGO_TEXT_CASES = Set.of("normal", "upper");
    public static final Set<String> LOGO_SHAPES = Set.of("rounded", "round");
    /** v1's reserved addresses plus the routes of the dynamic pages and the site admin (section 3.2). */
    static final Set<String> RESERVED_PATHS;

    static {
        Set<String> reserved = new HashSet<>(ClanSiteLayoutRules.RESERVED_PATHS);
        reserved.addAll(List.of("m", "me", "admin", "e"));
        RESERVED_PATHS = Set.copyOf(reserved);
    }

    private static final Pattern ANCHOR = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern FONT = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int MAX_ANCHOR = 32;
    /** A page template's version number (the website's PageOrigin.v, 1-1000). */
    private static final int MAX_ORIGIN_VERSION = 1000;
    private static final int MAX_LABEL = 40;
    private static final int MAX_DESCRIPTION = 160;
    private static final int MAX_FOOTER_TEXT = 280;
    private static final int MAX_ANNOUNCEMENT = 160;
    private static final int MAX_CHILD_DESCRIPTION = 80;
    private static final int MAX_ORDER = 12;
    private static final int MAX_OVERLAY = 90;
    private static final int MAX_WIDGET_VERSION = 1000;
    private static final int MAX_WIZARD_STEP = 40;

    /**
     * How many pages, and widgets on a page, the server's plan allows (section 10.5), never more than the hard limits. A site whose plan got
     * smaller keeps what it already has (section 10.4: the free limits apply to new pages and widgets only): {@link #keeping} lifts each
     * limit to what the stored draft already holds.
     */
    public record Limits(int pages, int widgetsPerPage, int keptPages, Map<String, Integer> keptWidgets) {
        public static final Limits PREMIUM = new Limits(MAX_PAGES, MAX_WIDGETS_PER_PAGE, 0, Map.of());

        public Limits {
            keptWidgets = Map.copyOf(keptWidgets);
        }

        public static Limits of(long pages, long widgetsPerPage) {
            return new Limits((int) Math.max(1, Math.min(MAX_PAGES, pages)), (int) Math.max(1, Math.min(MAX_WIDGETS_PER_PAGE, widgetsPerPage)), 0, Map.of());
        }

        /** These limits, raised to what {@code storedDraft} (either version, or null) already holds. */
        public Limits keeping(String storedDraft) {
            if (storedDraft == null || storedDraft.isBlank()) return this;
            DataArray pages;
            try {
                pages = DataObject.fromJson(storedDraft).optArray("pages").orElse(DataArray.empty());
            } catch (RuntimeException e) {
                return this;
            }
            Map<String, Integer> widgets = new HashMap<>();
            for (int i = 0; i < pages.length(); i++) {
                if (!pages.isType(i, DataType.OBJECT)) continue;
                DataObject page = pages.getObject(i);
                widgets.merge(page.getString("id", ""), countWidgets(page), Math::max);
            }
            return new Limits(pages(), widgetsPerPage(), pages.length(), widgets);
        }

        int pagesAllowed() {
            return Math.min(MAX_PAGES, Math.max(pages, keptPages));
        }

        int widgetsAllowed(String pageId) {
            return Math.min(MAX_WIDGETS_PER_PAGE, Math.max(widgetsPerPage, keptWidgets.getOrDefault(pageId, 0)));
        }
    }

    /** Every widget under a page of either version (each {@code widgets} array, however deep). */
    static int countWidgets(DataObject node) {
        int count = 0;
        for (String key : node.keys()) {
            if (node.isType(key, DataType.ARRAY)) {
                DataArray array = node.getArray(key);
                for (int i = 0; i < array.length(); i++) {
                    if (!array.isType(i, DataType.OBJECT)) continue;
                    if (key.equals("widgets")) count++;
                    else count += countWidgets(array.getObject(i));
                }
            } else if (node.isType(key, DataType.OBJECT)) {
                count += countWidgets(node.getObject(key));
            }
        }
        return count;
    }

    /**
     * The widget types a community website may not hold (plan C-9, docs/CLAN-AND-COMMUNITY-SITES.md section 7 in the RuneClan repo): the
     * website's {@code CLAN_ONLY_WIDGETS} ({@code packages/builder/src/v3/siteKind.ts}), whose copy is the shared fixture
     * {@code builder-v3/kinds/clan-only.json}. A test fails when the two differ.
     */
    public static final Set<String> CLAN_ONLY_WIDGETS = Set.of(
            "activity-feed", "adventure-log", "application-form", "boss-detail", "boss-grid", "citadel-chart", "citadel-grid", "clan-goals",
            "clan-history", "clan-stats", "clan-top", "coffer", "compare", "drop-log", "hall-of-fame", "item-detail", "joins-chart", "joins-weekly",
            "kill-tracker", "leaderboard", "leaderboard-race", "member-adventure-log", "member-badges", "member-card", "member-citadel",
            "member-directory", "member-gains", "member-points", "member-recap", "member-skills", "member-xp-chart", "member-xp-split", "milestones",
            "period-bar", "rank-breakdown", "rank-ladder", "rare-drops", "recap", "recap-picker", "roster", "season-standings", "skill-leaders",
            "spotlight", "stat-tiles", "top-list", "top-xp", "weekly-recap", "wrapped-recap", "xp-chart");

    /** The site type that holds no clan: {@link #clean(DataObject, Limits, String, List)} refuses clan-only widgets for it. */
    public static final String COMMUNITY = "community";

    /** The layout to store, or null with the reasons in {@code problems}: a clan website's (every site before plan C-9). */
    public static String clean(DataObject layout, Limits limits, List<String> problems) {
        return clean(layout, limits, null, problems);
    }

    /**
     * The layout to store for a site of {@code kind} ("clan", "community", or null for a clan site), or null with the reasons in
     * {@code problems}. A community website may hold no clan-only widget (plan C-9); each one is a problem, worded as the website words it.
     */
    public static String clean(DataObject layout, Limits limits, String kind, List<String> problems) {
        int before = problems.size();
        String out = shape(layout, limits, problems);
        if (COMMUNITY.equals(kind) && layout != null) clanOnlyWidgets(layout, problems);
        return problems.size() == before ? out : null;
    }

    /** One problem per clan-only widget in the layout, under its page's label (only called for a community website). */
    static void clanOnlyWidgets(DataObject layout, List<String> problems) {
        DataArray pages = layout.optArray("pages").orElse(null);
        if (pages == null) return;
        for (int p = 0; p < pages.length(); p++) {
            if (!pages.isType(p, DataType.OBJECT)) continue;
            DataObject page = pages.getObject(p);
            String title = page.getString("title", "").strip();
            String label = title.isEmpty() ? "A page" : "The page \"" + title + "\"";
            for (DataObject section : objects(page, "sections"))
                for (DataObject row : objects(section, "rows"))
                    for (DataObject cell : objects(row, "cells"))
                        for (DataObject widget : objects(cell, "widgets"))
                            if (CLAN_ONLY_WIDGETS.contains(widget.getString("type", "")))
                                problems.add(label + " has a clan-only widget. Community websites have no clan roster or stats: remove it.");
        }
    }

    /**
     * A copy of the layout with every clan-only widget taken out (plan C-13, a clan site going back to a community; the hub's
     * {@code ClanSiteLayoutRulesV2.withoutClanOnly}, kept in step): the cells stay, so the page keeps its shape. {@code removed} gets one
     * "page title: widget type" line per widget taken out. A null layout, or one that isn't v2, is returned as it is (the same
     * instance). Not called by the bot itself today (it holds only clan sites): it is the hub's rule, kept here so the two stay in step.
     */
    public static DataObject withoutClanOnly(DataObject layout, List<String> removed) {
        if (layout == null || layout.getInt("v", -1) != VERSION) return layout;
        DataObject copy = DataObject.fromJson(layout.toString());
        DataArray pages = copy.optArray("pages").orElse(null);
        if (pages == null) return copy;
        for (int p = 0; p < pages.length(); p++) {
            if (!pages.isType(p, DataType.OBJECT)) continue;
            DataObject page = pages.getObject(p);
            String title = page.getString("title", "").strip();
            for (DataObject section : objects(page, "sections"))
                for (DataObject row : objects(section, "rows"))
                    for (DataObject cell : objects(row, "cells")) {
                        DataArray widgets = cell.optArray("widgets").orElse(null);
                        if (widgets == null) continue;
                        List<Object> raw = widgets.toList();
                        DataArray kept = DataArray.empty();
                        for (int w = 0; w < widgets.length(); w++) {
                            String type = widgets.isType(w, DataType.OBJECT) ? widgets.getObject(w).getString("type", "") : "";
                            if (CLAN_ONLY_WIDGETS.contains(type)) removed.add((title.isEmpty() ? "A page" : title) + ": " + type);
                            else kept.add(raw.get(w));
                        }
                        cell.put("widgets", kept);
                    }
        }
        return copy;
    }

    private static List<DataObject> objects(DataObject parent, String key) {
        DataArray array = parent.optArray(key).orElse(null);
        if (array == null) return List.of();
        List<DataObject> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) if (array.isType(i, DataType.OBJECT)) out.add(array.getObject(i));
        return out;
    }

    /** The layout's shape checked and rebuilt from the known fields, or null with the reasons in {@code problems}. */
    private static String shape(DataObject layout, Limits limits, List<String> problems) {
        int before = problems.size();
        if (layout == null) {
            problems.add("Send the layout.");
            return null;
        }
        if (bytes(layout) > MAX_BYTES) {
            problems.add("The site is too big to save (" + (MAX_BYTES / 1024) + " KB at most). Remove some widgets or text.");
            return null;
        }
        if (layout.getInt("v", -1) != VERSION) problems.add("This layout is from another version of the builder. Reload the page.");

        Set<String> ids = new HashSet<>();
        DataObject out = DataObject.empty().put("v", VERSION);
        out.put("look", ClanSiteLayoutRules.look(layout.optObject("look").orElse(null), problems));
        out.put("style", style(object(layout, "style", problems, "The site's style"), problems));
        out.put("header", header(object(layout, "header", problems, "The header"), ids, problems));
        out.put("footer", footer(object(layout, "footer", problems, "The footer"), problems));

        Set<String> paths = new HashSet<>();
        Set<String> templates = new HashSet<>();
        DataArray pages = layout.optArray("pages").orElse(null);
        DataArray cleanPages = DataArray.empty();
        int allowed = limits.pagesAllowed();
        if (pages == null || pages.isEmpty()) problems.add("A site needs at least its Home page.");
        else if (pages.length() > allowed) {
            if (allowed >= MAX_PAGES) problems.add("A site can have up to " + MAX_PAGES + " pages.");
            else if (allowed > limits.pages()) problems.add("Your plan allows " + limits.pages() + " pages. This site keeps the " + allowed + " it has, but can't add more.");
            else problems.add("Your plan allows up to " + allowed + " pages. Premium allows " + MAX_PAGES + ".");
        } else {
            for (int i = 0; i < pages.length(); i++) {
                if (!pages.isType(i, DataType.OBJECT)) {
                    problems.add("Page " + (i + 1) + " isn't a page.");
                    continue;
                }
                cleanPages.add(page(pages.getObject(i), i == 0, limits, ids, paths, templates, problems));
            }
        }
        out.put("pages", cleanPages);
        if (!cleanPages.isEmpty()) pageLinks(out, problems);

        DataObject wizard = layout.optObject("wizard").orElse(null);
        if (wizard != null) {
            // v2 lets a step name be up to 40 characters (the model's WizardProgress); the rest is as v1
            DataObject progress = ClanSiteLayoutRules.wizard(wizard);
            String step = wizard.getString("step", "");
            if (!step.isEmpty() && step.length() <= MAX_WIZARD_STEP) progress.put("step", step);
            out.put("wizard", progress);
        }
        return problems.size() == before ? out.toString() : null;
    }

    // ---------- pages ----------

    private static DataObject page(DataObject page, boolean home, Limits limits, Set<String> ids, Set<String> paths, Set<String> templates, List<String> problems) {
        String id = id(page, ids, "page", problems);
        String title = page.getString("title", "").strip();
        String label = title.isEmpty() ? "A page" : "The page \"" + title + "\"";
        if (title.isEmpty() || title.length() > MAX_TITLE) problems.add("Give every page a title of 1 to " + MAX_TITLE + " characters.");
        String path = page.getString("path", "").strip();
        if (home) {
            if (!path.isEmpty()) problems.add("The first page is the home page, at the site's own address.");
        } else if (!PATH.matcher(path).matches() || path.length() > 32 || RESERVED_PATHS.contains(path)) {
            problems.add(label + " needs an address of lower-case letters, numbers and single dashes (up to 32).");
        } else if (!paths.add(path)) {
            problems.add("Two pages have the address /" + path + ".");
        }
        DataObject out = DataObject.empty().put("id", id).put("title", title).put("path", home ? "" : path).put("hidden", !home && page.getBoolean("hidden", false));
        out.put("access", choice(page, "access", ACCESS, "public", label + " has an unknown access level.", problems));
        if (page.hasKey("template") && !page.isNull("template")) {
            String template = page.getString("template", "");
            if (home) problems.add("The home page can't be a member, event, boss, item or account page.");
            else if (!TEMPLATES.contains(template)) problems.add(label + " is an unknown kind of page.");
            else if (!templates.add(template)) problems.add("A site can have only one " + template + " page.");
            out.put("template", template);
        }
        if (page.hasKey("origin") && !page.isNull("origin")) {
            // the page template it was made from (F14): { template: "members", v: 1 }; the website ships the templates themselves
            DataObject origin = page.optObject("origin").orElse(null);
            String template = origin == null ? "" : origin.getString("template", "");
            int v = origin == null || !origin.isType("v", DataType.INT) ? 0 : origin.getInt("v", 0);
            boolean onlyKnownKeys = origin != null && origin.keys().stream().allMatch(k -> k.equals("template") || k.equals("v"));
            if (!onlyKnownKeys || !ANCHOR.matcher(template).matches() || template.length() > MAX_ANCHOR || v < 1 || v > MAX_ORIGIN_VERSION)
                problems.add(label + " has a broken page template note.");
            else out.put("origin", DataObject.empty().put("template", template).put("v", v));
        }
        DataObject seo = page.optObject("seo").orElse(null);
        if (seo != null) {
            DataObject cleanSeo = DataObject.empty();
            String description = text(seo, "description", MAX_DESCRIPTION, label + "'s search description is longer than " + MAX_DESCRIPTION + " characters.", problems);
            if (description != null) cleanSeo.put("description", description);
            String image = seo.isNull("image") ? "" : seo.getString("image", "").strip();
            if (!image.isEmpty()) {
                if (!picture(image)) problems.add(label + "'s sharing picture must be an https picture address or an upload.");
                cleanSeo.put("image", image);
            }
            out.put("seo", cleanSeo);
        }

        DataArray sections = page.optArray("sections").orElse(DataArray.empty());
        DataArray cleanSections = DataArray.empty();
        if (sections.length() > MAX_SECTIONS) problems.add(label + " has more than " + MAX_SECTIONS + " sections.");
        else {
            Set<String> anchors = new HashSet<>();
            for (int i = 0; i < sections.length(); i++) {
                if (!sections.isType(i, DataType.OBJECT)) {
                    problems.add(label + " has a section that isn't one.");
                    continue;
                }
                cleanSections.add(section(sections.getObject(i), label, ids, anchors, problems));
            }
        }
        out.put("sections", cleanSections);

        int widgets = countWidgets(out);
        int allowed = limits.widgetsAllowed(id);
        if (widgets > allowed) {
            String limit;
            if (allowed >= MAX_WIDGETS_PER_PAGE) limit = "a page can hold " + MAX_WIDGETS_PER_PAGE + ".";
            else if (allowed > limits.widgetsPerPage()) limit = "your plan allows " + limits.widgetsPerPage() + " a page; this page keeps the " + allowed + " it had, but can't add more.";
            else limit = "your plan allows " + allowed + " a page (Premium allows " + MAX_WIDGETS_PER_PAGE + ").";
            problems.add(label + " has " + widgets + " widgets; " + limit);
        }
        return out;
    }

    private static DataObject section(DataObject section, String label, Set<String> ids, Set<String> anchors, List<String> problems) {
        String id = id(section, ids, "section", problems);
        DataObject out = DataObject.empty().put("id", id);
        String name = text(section, "label", MAX_LABEL, label + " has a section name longer than " + MAX_LABEL + " characters.", problems);
        if (name != null) out.put("label", name);
        out.put("width", choice(section, "width", WIDTHS, "wide", label + " has a section of an unknown width.", problems));
        out.put("background", background(section.optObject("background").orElse(null), label, problems));
        out.put("padding", responsive(section, "padding", DataObject.empty().put("y", "md").put("x", "none"), ClanSiteLayoutRulesV2::padding,
                label + " has a section with unknown spacing.", problems));
        // T-518: a section's own space between its rows (missing: the renderer's L)
        if (section.hasKey("gap") && !section.isNull("gap"))
            out.put("gap", responsive(section, "gap", "lg", ClanSiteLayoutRulesV2::space, label + " has a section with unknown spacing between its rows.", problems));
        out.put("height", responsive(section, "height", auto(), (h, k) -> height(h, k, true), label + " has a section with an impossible height.", problems));
        hide(section, out, label, problems);
        if (section.hasKey("anchor") && !section.isNull("anchor")) {
            String anchor = section.getString("anchor", "");
            if (!ANCHOR.matcher(anchor).matches() || anchor.length() > MAX_ANCHOR) problems.add(label + " has a section link name that isn't lower-case letters, numbers and dashes.");
            else if (!anchors.add(anchor)) problems.add(label + " has two sections with the link name #" + anchor + ".");
            out.put("anchor", anchor);
        }
        DataArray rows = section.optArray("rows").orElse(DataArray.empty());
        DataArray cleanRows = DataArray.empty();
        if (rows.isEmpty() || rows.length() > MAX_ROWS) problems.add(label + " has a section without 1 to " + MAX_ROWS + " rows.");
        else {
            for (int i = 0; i < rows.length(); i++) {
                if (!rows.isType(i, DataType.OBJECT)) {
                    problems.add(label + " has a row that isn't one.");
                    continue;
                }
                cleanRows.add(row(rows.getObject(i), label, ids, problems));
            }
        }
        return out.put("rows", cleanRows);
    }

    private static DataObject background(DataObject background, String label, List<String> problems) {
        DataObject b = background == null ? DataObject.empty() : background;
        String kind = choice(b, "kind", BACKGROUNDS, "none", label + " has a section with an unknown background.", problems);
        DataObject out = DataObject.empty().put("kind", kind);
        String image = b.isNull("image") ? "" : b.getString("image", "").strip();
        if (kind.equals("image") && image.isEmpty()) problems.add(label + " has a picture background without a picture.");
        if (!image.isEmpty()) {
            if (!picture(image)) problems.add(label + " has a background that isn't an https picture address or an upload.");
            out.put("image", image);
        }
        if (b.hasKey("overlay") && !b.isNull("overlay")) {
            Long overlay = wholeNumber(b, "overlay");
            if (overlay == null || overlay < 0 || overlay > MAX_OVERLAY) problems.add(label + " has a background shade out of range (0 to " + MAX_OVERLAY + "%).");
            else out.put("overlay", overlay);
        }
        if (b.hasKey("fixed")) out.put("fixed", b.getBoolean("fixed", false));
        return out;
    }

    private static DataObject row(DataObject row, String label, Set<String> ids, List<String> problems) {
        String id = id(row, ids, "row", problems);
        DataObject out = DataObject.empty().put("id", id);
        out.put("gap", responsive(row, "gap", "md", ClanSiteLayoutRulesV2::space, label + " has a row with unknown spacing.", problems));
        out.put("align", choice(row, "align", ALIGN, "stretch", label + " has a row with an unknown alignment.", problems));
        out.put("height", responsive(row, "height", auto(), (h, k) -> height(h, k, false), label + " has a row with an impossible height.", problems));
        DataArray cells = row.optArray("cells").orElse(DataArray.empty());
        DataArray cleanCells = DataArray.empty();
        if (cells.isEmpty() || cells.length() > MAX_CELLS) problems.add(label + " has a row without 1 to " + MAX_CELLS + " cells.");
        else {
            for (int i = 0; i < cells.length(); i++) {
                if (!cells.isType(i, DataType.OBJECT)) {
                    problems.add(label + " has a cell that isn't one.");
                    continue;
                }
                cleanCells.add(cell(cells.getObject(i), label, ids, problems));
            }
        }
        return out.put("cells", cleanCells);
    }

    private static DataObject cell(DataObject cell, String label, Set<String> ids, List<String> problems) {
        String id = id(cell, ids, "cell", problems);
        DataObject out = DataObject.empty().put("id", id);
        out.put("span", responsive(cell, "span", null, ClanSiteLayoutRulesV2::span, label + " has a cell of an impossible width (1 to " + GRID + " twelfths).", problems));
        DataObject order = cell.optObject("order").orElse(null);
        if (order != null) {
            DataObject cleanOrder = DataObject.empty();
            for (Bp bp : Bp.values()) {
                if (!order.hasKey(bp.name()) || order.isNull(bp.name())) continue;
                Long value = wholeNumber(order, bp.name());
                if (value != null && value >= 0 && value <= MAX_ORDER) cleanOrder.put(bp.name(), value);
                else problems.add(label + " has a cell with an impossible order.");
            }
            if (!cleanOrder.keys().isEmpty()) out.put("order", cleanOrder);
        }
        out.put("padding", responsive(cell, "padding", "none", ClanSiteLayoutRulesV2::space, label + " has a cell with unknown spacing.", problems));
        // the space between the cell's widgets (runeclans T-518): optional, kept only when the website sets it
        if (cell.hasKey("gap") && !cell.isNull("gap"))
            out.put("gap", responsive(cell, "gap", "md", ClanSiteLayoutRulesV2::space, label + " has a cell with unknown spacing between its widgets.", problems));
        out.put("valign", choice(cell, "valign", ALIGN, "start", label + " has a cell with an unknown alignment.", problems));
        // left to right (builder v3 F20, T-421): optional, and full width ("stretch") is stored as no field, the same as the website does
        String halign = choice(cell, "halign", ALIGN, "stretch", label + " has a cell with an unknown left-to-right alignment.", problems);
        if (!halign.equals("stretch")) out.put("halign", halign);
        out.put("surface", choice(cell, "surface", SURFACES, "none", label + " has a cell with an unknown look.", problems));
        hide(cell, out, label, problems);
        DataArray widgets = cell.optArray("widgets").orElse(DataArray.empty());
        DataArray cleanWidgets = DataArray.empty();
        if (widgets.length() > MAX_WIDGETS_PER_CELL) problems.add(label + " has a cell with more than " + MAX_WIDGETS_PER_CELL + " widgets.");
        else {
            for (int i = 0; i < widgets.length(); i++) {
                if (!widgets.isType(i, DataType.OBJECT)) {
                    problems.add(label + " has a widget that isn't one.");
                    continue;
                }
                cleanWidgets.add(widget(widgets.getObject(i), label, ids, problems));
            }
        }
        return out.put("widgets", cleanWidgets);
    }

    private static DataObject widget(DataObject widget, String label, Set<String> ids, List<String> problems) {
        String id = id(widget, ids, "widget", problems);
        String type = widget.getString("type", "");
        if (!TYPE.matcher(type).matches()) problems.add(label + " has a widget of an unknown kind.");
        long v = 1;
        if (widget.hasKey("v")) {
            Long raw = wholeNumber(widget, "v");
            if (raw != null && raw >= 1 && raw <= MAX_WIDGET_VERSION) v = raw;
            else problems.add(label + " has a widget from an unknown version. Reload the page.");
        }
        DataObject settings = widget.isType("settings", DataType.OBJECT) ? widget.getObject("settings") : DataObject.empty();
        if (bytes(settings) > MAX_SETTINGS_BYTES) problems.add(label + " has a widget with too much in it (" + (MAX_SETTINGS_BYTES / 1024) + " KB at most).");
        String unmet = widget.getString("unmet", "hide");
        DataObject out = DataObject.empty().put("id", id).put("type", type).put("v", v).put("settings", settings)
                .put("unmet", ClanSiteLayoutRules.UNMET.contains(unmet) ? unmet : "hide");
        if (widget.hasKey("height") && !widget.isNull("height")) {
            out.put("height", responsive(widget, "height", auto(), (h, k) -> height(h, k, false), label + " has a widget with an impossible height.", problems));
        }
        hide(widget, out, label, problems);
        return out;
    }

    // ---------- header, footer, style (sections 7 and 9) ----------

    private static DataObject header(DataObject header, Set<String> ids, List<String> problems) {
        DataObject h = header == null ? DataObject.empty() : header;
        DataObject out = DataObject.empty();
        out.put("layout", choice(h, "layout", HEADER_LAYOUTS, "logo-left", "The header has an unknown layout.", problems));
        DataObject logo = object(h, "logo", problems, "The header's logo");
        DataObject l = logo == null ? DataObject.empty() : logo;
        DataObject cleanLogo = DataObject.empty().put("show", l.getBoolean("show", true));
        String image = l.isNull("image") ? "" : l.getString("image", "").strip();
        if (!image.isEmpty()) {
            if (!picture(image)) problems.add("The header's logo must be an https picture address or an upload.");
            cleanLogo.put("image", image);
        }
        String text = choice(l, "text", LOGO_TEXT, "name", "The header's logo has an unknown text choice.", problems);
        cleanLogo.put("text", text);
        String custom = text(l, "custom", MAX_LABEL, "The header's logo text is longer than " + MAX_LABEL + " characters.", problems);
        if (custom != null) cleanLogo.put("custom", custom);
        else if (text.equals("custom")) problems.add("Type the header's logo text, or show the clan name.");
        cleanLogo.put("height", whole(l, "height", 24, 96, 40, "The header's logo height must be 24 to 96.", problems));
        optionalChoice(l, cleanLogo, "textColour", LOGO_TEXT_COLOURS, "The header's logo text has an unknown colour.", problems);
        optionalChoice(l, cleanLogo, "textCase", LOGO_TEXT_CASES, "The header's logo text has an unknown letter case.", problems);
        optionalChoice(l, cleanLogo, "shape", LOGO_SHAPES, "The header's logo has an unknown shape.", problems);
        out.put("logo", cleanLogo);

        DataArray nav = h.optArray("nav").orElse(DataArray.empty());
        DataArray cleanNav = DataArray.empty();
        int count = 0;
        for (int i = 0; i < nav.length(); i++) {
            if (!nav.isType(i, DataType.OBJECT)) {
                problems.add("The menu has an item that isn't one.");
                continue;
            }
            DataObject item = nav.getObject(i);
            DataObject cleanItem = navLink(item, false, ids, problems);
            count++;
            if (item.hasKey("badge") && !item.isNull("badge")) {
                if (!"events".equals(item.getString("badge", ""))) problems.add("A menu item has an unknown badge.");
                cleanItem.put("badge", "events");
            }
            DataArray children = item.optArray("children").orElse(null);
            if (children != null) {
                DataArray cleanChildren = DataArray.empty();
                for (int c = 0; c < children.length(); c++) {
                    if (!children.isType(c, DataType.OBJECT)) {
                        problems.add("The menu has an item that isn't one.");
                        continue;
                    }
                    DataObject child = children.getObject(c);
                    if (child.hasKey("children")) problems.add("Drop-down menus go one level deep: an item inside a drop-down can't have its own.");
                    cleanChildren.add(navLink(child, true, ids, problems));
                    count++;
                }
                cleanItem.put("children", cleanChildren);
            }
            cleanNav.add(cleanItem);
        }
        if (count > MAX_NAV_ITEMS) problems.add("The menu can have up to " + MAX_NAV_ITEMS + " items, counting the ones in drop-downs.");
        out.put("nav", cleanNav);

        DataObject slots = object(h, "slots", problems, "The header's slots");
        DataObject s = slots == null ? DataObject.empty() : slots;
        Set<String> kinds = new HashSet<>();
        out.put("slots", DataObject.empty().put("left", slots(s, "left", kinds, problems)).put("right", slots(s, "right", kinds, problems)));
        out.put("sticky", h.getBoolean("sticky", false));
        out.put("transparent", choice(h, "transparent", TRANSPARENT, "never", "The header has an unknown see-through setting.", problems));
        out.put("height", choice(h, "height", HEADER_HEIGHTS, "normal", "The header has an unknown height.", problems));
        out.put("mobile", choice(h, "mobile", MOBILE_NAV, "drawer", "The header has an unknown phone menu.", problems));

        DataObject announcement = object(h, "announcement", problems, "The announcement bar");
        if (announcement != null) {
            DataObject a = DataObject.empty();
            String message = text(announcement, "text", MAX_ANNOUNCEMENT, "The announcement is longer than " + MAX_ANNOUNCEMENT + " characters.", problems);
            if (message == null) problems.add("Type the announcement, or switch the bar off.");
            a.put("text", message == null ? "" : message);
            if (announcement.hasKey("link") && !announcement.isNull("link")) a.put("link", link(announcement, "The announcement", problems));
            if (announcement.hasKey("until") && !announcement.isNull("until")) {
                String until = announcement.getString("until", "");
                if (!instant(until)) problems.add("The announcement's end date isn't a date.");
                a.put("until", until);
            }
            a.put("tone", choice(announcement, "tone", TONES, "info", "The announcement has an unknown colour.", problems));
            out.put("announcement", a);
        }
        if (h.hasKey("accentLine") && !h.isNull("accentLine")) {
            if (!h.isType("accentLine", DataType.BOOLEAN)) problems.add("The header's coloured line must be on or off.");
            else out.put("accentLine", h.getBoolean("accentLine"));
        }
        optionalChoice(h, out, "navAlign", NAV_ALIGN, "The header's menu has an unknown position.", problems);
        optionalChoice(h, out, "navActive", NAV_ACTIVE, "The header's menu has an unknown look for the current page.", problems);
        return out;
    }

    private static DataObject navLink(DataObject item, boolean child, Set<String> ids, List<String> problems) {
        String id = id(item, ids, "menu item", problems);
        String label = text(item, "label", MAX_LABEL, "Menu items have names of 1 to " + MAX_LABEL + " characters.", problems);
        if (label == null) problems.add("Menu items have names of 1 to " + MAX_LABEL + " characters.");
        DataObject out = DataObject.empty().put("id", id).put("label", label == null ? "" : label);
        String what = "The menu item \"" + (label == null ? "" : label) + "\"";
        if (item.hasKey("link") && !item.isNull("link")) out.put("link", link(item, what, problems));
        else if (child) problems.add(what + " in a drop-down needs a link.");
        if (child) {
            String description = text(item, "description", MAX_CHILD_DESCRIPTION, what + " has a description longer than " + MAX_CHILD_DESCRIPTION + " characters.", problems);
            if (description != null) out.put("description", description);
        }
        return out;
    }

    /** One side of the header. Each kind of item appears once in the whole header, except buttons ({@code kinds} carries the left side's). */
    private static DataArray slots(DataObject slots, String side, Set<String> kinds, List<String> problems) {
        DataArray list = slots.optArray(side).orElse(DataArray.empty());
        DataArray out = DataArray.empty();
        if (list.length() > MAX_SLOTS) {
            problems.add("Each side of the header can hold up to " + MAX_SLOTS + " items.");
            return out;
        }
        for (int i = 0; i < list.length(); i++) {
            if (!list.isType(i, DataType.OBJECT)) {
                problems.add("The header has an item that isn't one.");
                continue;
            }
            DataObject slot = list.getObject(i);
            String kind = slot.getString("kind", "");
            if (!SLOT_KINDS.contains(kind)) problems.add("The header has an item of an unknown kind.");
            else if (!kind.equals("button") && !kinds.add(kind)) problems.add("The header has the " + kind + " item twice.");
            DataObject cleanSlot = DataObject.empty().put("kind", kind);
            if (slot.isType("settings", DataType.OBJECT)) {
                DataObject settings = slot.getObject("settings");
                if (bytes(settings) > MAX_SLOT_SETTINGS_BYTES) problems.add("A header item has too much in it (" + (MAX_SLOT_SETTINGS_BYTES / 1024) + " KB at most).");
                cleanSlot.put("settings", settings);
            }
            out.add(cleanSlot);
        }
        return out;
    }

    private static DataObject footer(DataObject footer, List<String> problems) {
        DataObject f = footer == null ? DataObject.empty() : footer;
        DataArray columns = f.optArray("columns").orElse(DataArray.empty());
        DataArray cleanColumns = DataArray.empty();
        if (columns.length() > MAX_FOOTER_COLUMNS) problems.add("The footer can have up to " + MAX_FOOTER_COLUMNS + " columns.");
        else {
            for (int i = 0; i < columns.length(); i++) {
                if (!columns.isType(i, DataType.OBJECT)) {
                    problems.add("The footer has a column that isn't one.");
                    continue;
                }
                DataObject column = columns.getObject(i);
                String title = text(column, "title", MAX_LABEL, "Footer column titles are up to " + MAX_LABEL + " characters.", problems);
                DataArray links = column.optArray("links").orElse(DataArray.empty());
                DataArray cleanLinks = DataArray.empty();
                if (links.length() > MAX_FOOTER_LINKS) problems.add("A footer column can have up to " + MAX_FOOTER_LINKS + " links.");
                else {
                    for (int k = 0; k < links.length(); k++) {
                        if (!links.isType(k, DataType.OBJECT)) {
                            problems.add("The footer has a link that isn't one.");
                            continue;
                        }
                        DataObject link = links.getObject(k);
                        String label = text(link, "label", MAX_LABEL, "Footer links have names of 1 to " + MAX_LABEL + " characters.", problems);
                        if (label == null) problems.add("Footer links have names of 1 to " + MAX_LABEL + " characters.");
                        cleanLinks.add(DataObject.empty().put("label", label == null ? "" : label).put("link", link(link, "A footer link", problems)));
                    }
                }
                cleanColumns.add(DataObject.empty().put("title", title == null ? "" : title).put("links", cleanLinks));
            }
        }
        DataObject out = DataObject.empty().put("columns", cleanColumns);
        String text = text(f, "text", MAX_FOOTER_TEXT, "The footer text is longer than " + MAX_FOOTER_TEXT + " characters.", problems);
        if (text != null) out.put("text", text);
        if (f.hasKey("hidden") && !f.isNull("hidden")) {
            if (!f.isType("hidden", DataType.BOOLEAN)) problems.add("The footer's hide setting must be on or off.");
            else if (f.getBoolean("hidden")) out.put("hidden", true);
        }
        return out.put("showSocial", f.getBoolean("showSocial", false)).put("badge", f.getBoolean("badge", true));
    }

    private static DataObject style(DataObject style, List<String> problems) {
        DataObject out = DataObject.empty();
        if (style == null) return out;
        DataObject colours = object(style, "colours", problems, "The site's colours");
        if (colours != null) {
            DataObject clean = DataObject.empty();
            for (String key : colours.keys()) {
                if (!COLOURS.contains(key)) continue;
                String colour = colours.isNull(key) ? "" : colours.getString(key, "");
                if (colour.isEmpty()) continue;
                if (!ClanSiteLayoutRules.HEX.matcher(colour).matches()) problems.add("Site colours must be #rrggbb colours.");
                clean.put(key, colour);
            }
            out.put("colours", clean);
        }
        DataObject fonts = object(style, "fonts", problems, "The site's fonts");
        if (fonts != null) {
            DataObject clean = DataObject.empty();
            for (String key : List.of("heading", "body")) {
                String font = fonts.isNull(key) ? "" : fonts.getString(key, "");
                if (font.isEmpty()) continue;
                if (!FONT.matcher(font).matches() || font.length() > 32) problems.add("The site uses a font that isn't on the list.");
                clean.put(key, font);
            }
            out.put("fonts", clean);
        }
        optionalChoice(style, out, "radius", RADIUS, "The site has an unknown corner rounding.", problems);
        optionalChoice(style, out, "density", DENSITY, "The site has an unknown spacing.", problems);
        optionalChoice(style, out, "glow", GLOW, "The site has an unknown glow setting.", problems);
        optionalChoice(style, out, "buttons", BUTTONS, "The site has an unknown button look.", problems);
        optionalChoice(style, out, "titles", TITLES, "The site has an unknown panel title look.", problems);
        optionalChoice(style, out, "gutter", GUTTERS, "The site has unknown side margins.", problems);
        optionalChoice(style, out, "tabs", TABS, "The site has an unknown look for its time-frame switches.", problems);
        optionalChoice(style, out, "motion", MOTION, "The site has an unknown animation style.", problems);
        return out;
    }

    // ---------- values ----------

    /**
     * The header's and footer's links to pages must name a page of this site (the model's {@code validateLayout} does the same); links inside
     * widget settings are the website's business.
     */
    private static void pageLinks(DataObject out, List<String> problems) {
        Set<String> pageIds = new HashSet<>();
        DataArray pages = out.getArray("pages");
        for (int i = 0; i < pages.length(); i++) pageIds.add(pages.getObject(i).getString("id", ""));
        List<DataObject> links = new ArrayList<>();
        DataObject header = out.getObject("header");
        DataArray nav = header.getArray("nav");
        for (int i = 0; i < nav.length(); i++) {
            DataObject item = nav.getObject(i);
            item.optObject("link").ifPresent(links::add);
            DataArray children = item.optArray("children").orElse(DataArray.empty());
            for (int c = 0; c < children.length(); c++) children.getObject(c).optObject("link").ifPresent(links::add);
        }
        header.optObject("announcement").flatMap(a -> a.optObject("link")).ifPresent(links::add);
        DataArray columns = out.getObject("footer").getArray("columns");
        for (int i = 0; i < columns.length(); i++) {
            DataArray footerLinks = columns.getObject(i).getArray("links");
            for (int k = 0; k < footerLinks.length(); k++) footerLinks.getObject(k).optObject("link").ifPresent(links::add);
        }
        for (DataObject link : links) {
            if ("page".equals(link.getString("kind", "")) && !pageIds.contains(link.getString("pageId", ""))) {
                problems.add("A link in the menu or footer goes to a page that doesn't exist any more. Pick another page.");
                return;
            }
        }
    }

    /** A link field (section 4.2): a page of this site (by id, and an optional section link name), an https address, or the Discord invite. */
    private static DataObject link(DataObject holder, String what, List<String> problems) {
        DataObject link = holder.isType("link", DataType.OBJECT) ? holder.getObject("link") : null;
        if (link == null) {
            problems.add(what + " has a broken link.");
            return DataObject.empty().put("kind", "invite");
        }
        String kind = link.getString("kind", "");
        switch (kind) {
            case "page" -> {
                String pageId = link.getString("pageId", "");
                if (!ID.matcher(pageId).matches()) problems.add(what + " links to a page that doesn't exist.");
                DataObject out = DataObject.empty().put("kind", "page").put("pageId", pageId);
                if (link.hasKey("anchor") && !link.isNull("anchor")) {
                    String anchor = link.getString("anchor", "");
                    if (!ANCHOR.matcher(anchor).matches() || anchor.length() > MAX_ANCHOR) problems.add(what + " links to a section name that isn't one.");
                    out.put("anchor", anchor);
                }
                return out;
            }
            case "url" -> {
                String href = link.getString("href", "").strip();
                if (!https(href)) problems.add(what + " must link to an https address.");
                return DataObject.empty().put("kind", "url").put("href", href);
            }
            case "invite" -> {
                return DataObject.empty().put("kind", "invite");
            }
            default -> {
                problems.add(what + " has a broken link.");
                return DataObject.empty().put("kind", "invite");
            }
        }
    }

    static boolean https(String url) {
        if (url.isEmpty() || url.length() > MAX_URL) return false;
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean instant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private interface Value {
        /** The value of {@code holder.key} to store, or null when it isn't a good one. */
        Object clean(DataObject holder, String key);
    }

    /**
     * A {@code Responsive<T>} value: {@code lg} required, {@code md} and {@code base} optional (section 3.4 fills them in when rendering).
     * A missing field becomes {@code {lg: fallback}}; a missing field with no fallback is a problem.
     */
    private static DataObject responsive(DataObject holder, String key, Object fallback, Value value, String problem, List<String> problems) {
        if (!holder.hasKey(key) || holder.isNull(key)) {
            if (fallback == null) problems.add(problem);
            return DataObject.empty().put("lg", fallback);
        }
        if (!holder.isType(key, DataType.OBJECT)) {
            problems.add(problem);
            return DataObject.empty().put("lg", fallback);
        }
        DataObject raw = holder.getObject(key);
        DataObject out = DataObject.empty();
        for (Bp bp : List.of(Bp.lg, Bp.md, Bp.base)) {
            if (!raw.hasKey(bp.name()) || raw.isNull(bp.name())) {
                if (bp == Bp.lg) problems.add(problem);
                continue;
            }
            Object clean = value.clean(raw, bp.name());
            if (clean == null) problems.add(problem);
            else out.put(bp.name(), clean);
        }
        return out;
    }

    private static Object space(DataObject holder, String key) {
        String value = holder.isType(key, DataType.STRING) ? holder.getString(key) : null;
        return value != null && SPACE.contains(value) ? value : null;
    }

    private static Object padding(DataObject holder, String key) {
        if (!holder.isType(key, DataType.OBJECT)) return null;
        DataObject p = holder.getObject(key);
        Object y = space(p, "y");
        Object x = space(p, "x");
        if (y == null || x == null) return null;
        DataObject out = DataObject.empty().put("y", y).put("x", x);
        // T-518: an optional space below the content when it differs from the space above
        if (p.hasKey("bottom") && !p.isNull("bottom")) {
            Object bottom = space(p, "bottom");
            if (bottom == null) return null;
            out.put("bottom", bottom);
        }
        return out;
    }

    private static Object span(DataObject holder, String key) {
        Long n = wholeNumber(holder, key);
        return n != null && n >= 1 && n <= GRID ? n : null;
    }

    private static DataObject auto() {
        return DataObject.empty().put("mode", "auto");
    }

    /** A {@code Height}: auto, fixed or min (80 to 1600 px), or, for sections only, a share of the screen (20 to 100). */
    private static Object height(DataObject holder, String key, boolean viewport) {
        if (!holder.isType(key, DataType.OBJECT)) return null;
        DataObject h = holder.getObject(key);
        String mode = h.getString("mode", "");
        return switch (mode) {
            case "auto" -> auto();
            case "fixed", "min" -> {
                Long px = wholeNumber(h, "px");
                yield px != null && px >= 80 && px <= 1600 ? DataObject.empty().put("mode", mode).put("px", px) : null;
            }
            case "viewport" -> {
                Long pct = wholeNumber(h, "pct");
                yield viewport && pct != null && pct >= 20 && pct <= 100 ? DataObject.empty().put("mode", mode).put("pct", pct) : null;
            }
            default -> null;
        };
    }

    /** A whole number at {@code holder.key} (2 and 2.0 both count), or null. */
    private static Long wholeNumber(DataObject holder, String key) {
        if (!holder.hasKey(key) || holder.isNull(key)) return null;
        Object raw = holder.get(key);
        return raw instanceof Number n && whole(n) ? n.longValue() : null;
    }

    /** {@code hide}: which breakpoints a part is hidden on; only {@code true} entries are kept, and the key only when one is. */
    private static void hide(DataObject holder, DataObject out, String label, List<String> problems) {
        if (!holder.hasKey("hide") || holder.isNull("hide")) return;
        if (!holder.isType("hide", DataType.OBJECT)) {
            problems.add(label + " hides something in an unknown way.");
            return;
        }
        DataObject hide = holder.getObject("hide");
        DataObject clean = DataObject.empty();
        Set<Bp> hidden = EnumSet.noneOf(Bp.class);
        for (Bp bp : Bp.values()) if (hide.getBoolean(bp.name(), false)) hidden.add(bp);
        for (Bp bp : hidden) clean.put(bp.name(), true);
        if (!hidden.isEmpty()) out.put("hide", clean);
    }

    private static String choice(DataObject holder, String key, Set<String> allowed, String fallback, String problem, List<String> problems) {
        if (!holder.hasKey(key) || holder.isNull(key)) return fallback;
        String value = holder.getString(key, "");
        if (!allowed.contains(value)) {
            problems.add(problem);
            return fallback;
        }
        return value;
    }

    private static void optionalChoice(DataObject holder, DataObject out, String key, Set<String> allowed, String problem, List<String> problems) {
        if (!holder.hasKey(key) || holder.isNull(key)) return;
        out.put(key, choice(holder, key, allowed, "", problem, problems));
    }

    /** Text cut of surrounding spaces; null when missing or blank, a problem when longer than {@code max}. */
    private static String text(DataObject holder, String key, int max, String problem, List<String> problems) {
        if (!holder.hasKey(key) || holder.isNull(key)) return null;
        String value = holder.getString(key, "").strip();
        if (value.isEmpty()) return null;
        if (value.length() > max) problems.add(problem);
        return value;
    }

    private static long whole(DataObject holder, String key, long min, long max, long fallback, String problem, List<String> problems) {
        if (!holder.hasKey(key) || holder.isNull(key)) return fallback;
        Long value = wholeNumber(holder, key);
        if (value != null && value >= min && value <= max) return value;
        problems.add(problem);
        return fallback;
    }

    private static boolean whole(Number n) {
        return n.doubleValue() == Math.rint(n.doubleValue()) && !Double.isInfinite(n.doubleValue());
    }

    /** An optional object field: null when missing, a problem when it is something else. */
    private static DataObject object(DataObject holder, String key, List<String> problems, String what) {
        if (!holder.hasKey(key) || holder.isNull(key)) return null;
        if (!holder.isType(key, DataType.OBJECT)) {
            problems.add(what + " couldn't be read. Reload the page and try again.");
            return null;
        }
        return holder.getObject(key);
    }
}
