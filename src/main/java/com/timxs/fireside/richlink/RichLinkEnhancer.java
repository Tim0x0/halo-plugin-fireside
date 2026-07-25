package com.timxs.fireside.richlink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 将正文中的分享链接替换为占位卡片 HTML（零 HTTP）。
 * 真封面/标题由前台调官方 link-detail 填充；本类只做识别 + 可点占位壳。
 * <p>
 * 平台：音视频白名单优先（音乐/视频卡）；其余独立 http(s) 链走通用网页卡。
 * 幂等：已在 {@code .fireside-rich-link} 内的链接不再处理。
 * 启发式：仅「像分享主链」的锚点升级（块内几乎只有该链接），避免长句中的内联链接变大卡。
 * 顺序：仿微信朋友圈——正文在上，分享卡统一挪到内容末尾。
 */
public final class RichLinkEnhancer {

    private static final int MAX_HTML_CHARS = 50_000;

    private RichLinkEnhancer() {
    }

    /**
     * 增强正文 HTML；blank 原样返回。
     */
    public static String enhance(String html) {
        if (StringUtils.isBlank(html)) {
            return html;
        }
        // 快路径：无已有卡片且无平台候选时跳过（已有卡片仍可能要调顺序）
        String lowerHtml = html.toLowerCase(Locale.ROOT);
        boolean hasCard = lowerHtml.contains("fireside-rich-link");
        if (!hasCard && !mightContainCandidate(lowerHtml)) {
            return html;
        }
        // 超长正文只扫描前缀做识别；输出仍拼回未扫描后缀，避免截断丢文
        boolean truncated = html.length() > MAX_HTML_CHARS;
        String head = truncated ? html.substring(0, MAX_HTML_CHARS) : html;
        String tail = truncated ? html.substring(MAX_HTML_CHARS) : "";
        Document doc = Jsoup.parseBodyFragment(head);
        doc.outputSettings().prettyPrint(false);
        // absUrl 依赖 baseUri；同步侧 clean 后多为绝对链，此处兜底相对 href
        doc.setBaseUri("https://localhost/");

        List<Element> anchors = new ArrayList<>(doc.select("a[href]"));
        List<Element> newCards = new ArrayList<>();
        for (Element anchor : anchors) {
            if (anchor.closest(".fireside-rich-link") != null) {
                continue;
            }
            if (!anchor.hasParent()) {
                continue;
            }
            String href = resolveHref(anchor);
            var platform = RichLinkPlatform.matchShare(href);
            if (platform.isEmpty()) {
                continue;
            }
            if (!looksLikeShareLink(anchor)) {
                continue;
            }
            Element card = buildCard(doc, href, platform.get(), anchorText(anchor));
            // 先去掉原位置的分享链（独占块整块删，避免空 <p>），卡片稍后统一置底
            Element block = shareBlockParent(anchor);
            if (block != null && isShareOnlyBlock(block, anchor)) {
                block.remove();
            } else {
                anchor.remove();
            }
            newCards.add(card);
        }
        // 新卡与历史卡一并移到 body 末尾：正文在上、分享卡在下
        for (Element card : newCards) {
            doc.body().appendChild(card);
        }
        boolean reordered = moveRichLinksToEnd(doc.body());
        if (newCards.isEmpty() && !reordered) {
            return html;
        }
        pruneEmptyBlocks(doc.body());
        return doc.body().html() + tail;
    }

    /**
     * 将所有富链接卡片按原相对顺序移到 body 子节点末尾。
     *
     * @return 是否发生了位置变化
     */
    private static boolean moveRichLinksToEnd(Element body) {
        List<Element> cards = new ArrayList<>(body.select(".fireside-rich-link"));
        if (cards.isEmpty()) {
            return false;
        }
        if (cardsAreTrailingSuffix(body, cards)) {
            return false;
        }
        for (Element card : cards) {
            card.remove();
        }
        for (Element card : cards) {
            body.appendChild(card);
        }
        return true;
    }

    /** body 直接子节点是否以 cards 的完整序列结尾（已是「正文 + 卡」顺序） */
    private static boolean cardsAreTrailingSuffix(Element body, List<Element> cards) {
        var children = body.children();
        int n = children.size();
        int m = cards.size();
        if (m > n) {
            return false;
        }
        for (int i = 0; i < m; i++) {
            if (!children.get(n - m + i).equals(cards.get(i))) {
                return false;
            }
        }
        // 卡片不得仍嵌在其它块内（select 能取到，但不是 body 直接子节点）
        for (Element card : cards) {
            if (card.parent() != body) {
                return false;
            }
        }
        return true;
    }

    /** 删除去掉分享链后留下的空段落 */
    private static void pruneEmptyBlocks(Element body) {
        for (Element el : new ArrayList<>(body.select("p, div"))) {
            if (el.hasClass("fireside-rich-link")) {
                continue;
            }
            if (el.select(".fireside-rich-link").isEmpty()
                && !el.hasText()
                && el.select("img, video, audio, iframe, br").isEmpty()) {
                el.remove();
            }
        }
    }

    /**
     * 入参须已 toLowerCase；调用方仅在「尚无卡片」时使用。
     * 通用网页卡后任意 http(s)/href 都可能是候选，不再只扫音视频域名。
     */
    private static boolean mightContainCandidate(String lowerHtml) {
        return lowerHtml.contains("http://") || lowerHtml.contains("https://")
            || lowerHtml.contains("href=");
    }

    private static String resolveHref(Element anchor) {
        String abs = anchor.absUrl("href");
        if (StringUtils.isNotBlank(abs)) {
            return abs.trim();
        }
        return StringUtils.trimToEmpty(anchor.attr("href"));
    }

    private static String anchorText(Element anchor) {
        return StringUtils.trimToEmpty(anchor.text());
    }

    /**
     * 分享主链启发式：父块在去掉该 &lt;a&gt; 后几乎无可见文本/其它元素。
     */
    private static boolean looksLikeShareLink(Element anchor) {
        Element block = shareBlockParent(anchor);
        if (block == null) {
            // 无块级父时仍升级（如 body 下裸 a）
            return true;
        }
        return isShareOnlyBlock(block, anchor);
    }

    private static Element shareBlockParent(Element anchor) {
        Element p = anchor.parent();
        while (p != null && !isBlock(p) && !(p.tagName().equals("body"))) {
            p = p.parent();
        }
        if (p == null || p.tagName().equals("body") || p.tagName().equals("#root")) {
            return null;
        }
        return isBlock(p) ? p : null;
    }

    private static boolean isBlock(Element el) {
        String tag = el.normalName();
        return tag.equals("p") || tag.equals("div") || tag.equals("li")
            || tag.equals("section") || tag.equals("article");
    }

    private static boolean isShareOnlyBlock(Element block, Element anchor) {
        // 块内另有链接/媒体 → 不像「只分享一条链」
        for (Element child : block.select("a[href], img, video, audio, iframe")) {
            // child 就是本锚点，或其位于本锚点内部（如 a > img）时忽略
            if (child.equals(anchor) || child.parents().contains(anchor)) {
                continue;
            }
            return false;
        }
        // 去掉本锚点后剩余可见文本；允许极短残留（标点），长句内联链不升级
        Element clone = block.clone();
        String targetHref = resolveHref(anchor);
        for (Element a : clone.select("a[href]")) {
            if (targetHref.equals(resolveHref(a))) {
                a.remove();
            }
        }
        clone.select("br").remove();
        String rest = clone.text().replace(' ', ' ').trim();
        return rest.length() <= 4;
    }

    private static Element buildCard(Document doc, String href, RichLinkPlatform platform,
        String anchorText) {
        String title = defaultTitle(platform, anchorText, href);
        String kind = platform.getKind().cssSuffix();

        Element card = doc.createElement("div");
        card.attr("class", "fireside-rich-link fireside-rich-link--" + kind + " is-loading");
        card.attr("data-rich-link", "true");
        card.attr("data-href", href);
        card.attr("data-kind", kind);
        card.attr("data-platform", platform.getId());

        Element a = doc.createElement("a");
        a.attr("class", "fireside-rich-link__anchor");
        a.attr("href", href);
        a.attr("target", "_blank");
        a.attr("rel", "noopener nofollow noreferrer");

        Element cover = doc.createElement("img");
        cover.attr("class", "fireside-rich-link__cover");
        cover.attr("src", platform.defaultCoverPath());
        cover.attr("alt", "");
        cover.attr("loading", "lazy");
        // 外链封面跨站只带本站 origin，便于对方图床按域名白名单放行
        cover.attr("referrerpolicy", "strict-origin-when-cross-origin");

        Element meta = doc.createElement("div");
        meta.attr("class", "fireside-rich-link__meta");

        Element titleEl = doc.createElement("div");
        titleEl.attr("class", "fireside-rich-link__title");
        titleEl.text(title);

        Element subEl = doc.createElement("div");
        subEl.attr("class", "fireside-rich-link__sub");
        // 通用卡副标题用主机名（如 baike.baidu.com），音视频仍用平台展示名
        subEl.text(platform == RichLinkPlatform.GENERIC
            ? RichLinkPlatform.hostLabel(href)
            : platform.getDisplayName());

        meta.appendChild(titleEl);
        meta.appendChild(subEl);

        Element badge = doc.createElement("span");
        badge.attr("class", "fireside-rich-link__badge");
        badge.attr("aria-hidden", "true");

        a.appendChild(cover);
        a.appendChild(meta);
        a.appendChild(badge);
        card.appendChild(a);
        return card;
    }

    private static String defaultTitle(RichLinkPlatform platform, String anchorText, String href) {
        if (StringUtils.isNotBlank(anchorText)) {
            String t = anchorText.trim();
            // 锚文本是裸 URL 时用平台名 / 主机名
            if (!t.equals(href) && !looksLikeUrl(t) && t.length() <= 80) {
                return t;
            }
        }
        if (platform == RichLinkPlatform.GENERIC) {
            return RichLinkPlatform.hostLabel(href);
        }
        return platform.getDisplayName();
    }

    private static boolean looksLikeUrl(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.");
    }

}
