package com.timxs.fireside.richlink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RichLinkEnhancerTest {

    @Test
    void enhance_neteaseShareParagraph_becomesCard() {
        String html = "<p><a href=\"https://music.163.com/song?id=123456\">夜曲</a></p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).contains("fireside-rich-link");
        assertThat(out).contains("data-platform=\"netease\"");
        assertThat(out).contains("data-kind=\"music\"");
        assertThat(out).contains("data-href=\"https://music.163.com/song?id=123456\"");
        assertThat(out).contains("夜曲");
        assertThat(out).contains("网易云音乐");
    }

    @Test
    void enhance_bilibiliVideo_becomesVideoCard() {
        String html = "<p><a href=\"https://www.bilibili.com/video/BV1xx411c7mD\">测试视频</a></p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).contains("data-platform=\"bilibili\"");
        assertThat(out).contains("data-kind=\"video\"");
        assertThat(out).contains("fireside-rich-link--video");
    }

    @Test
    void enhance_inlineLinkInSentence_notUpgraded() {
        String html = "<p>今天听了<a href=\"https://music.163.com/song?id=1\">这首歌</a>，真好听</p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).doesNotContain("fireside-rich-link");
        assertThat(out).contains("music.163.com");
    }

    @Test
    void enhance_textThenShareLink_cardGoesAfterText() {
        // 链接在前、说明在后 → 朋友圈顺序应为正文在上、卡在下
        String html = "<p><a href=\"https://music.163.com/song?id=1\">歌</a></p>"
            + "<p>今天循环单曲</p>";
        String out = RichLinkEnhancer.enhance(html);
        int textAt = out.indexOf("今天循环单曲");
        int cardAt = out.indexOf("fireside-rich-link");
        assertThat(textAt).isGreaterThanOrEqualTo(0);
        assertThat(cardAt).isGreaterThanOrEqualTo(0);
        assertThat(textAt).isLessThan(cardAt);
    }

    @Test
    void enhance_existingCardBeforeText_reordersToEnd() {
        String html = "<div class=\"fireside-rich-link fireside-rich-link--music\" "
            + "data-rich-link=\"true\" data-href=\"https://music.163.com/song?id=1\" "
            + "data-kind=\"music\" data-platform=\"netease\">"
            + "<a class=\"fireside-rich-link__anchor\" href=\"https://music.163.com/song?id=1\">x</a>"
            + "</div><p>听完心情不错</p>";
        String out = RichLinkEnhancer.enhance(html);
        int textAt = out.indexOf("听完心情不错");
        int cardAt = out.indexOf("fireside-rich-link");
        assertThat(textAt).isLessThan(cardAt);
    }

    @Test
    void enhance_alreadyCard_isIdempotent() {
        String once = RichLinkEnhancer.enhance(
            "<p><a href=\"https://y.qq.com/n/ryqq/songDetail/001\">歌</a></p>");
        String twice = RichLinkEnhancer.enhance(once);
        assertThat(twice).isEqualTo(once);
        assertThat(twice.split("fireside-rich-link--music", -1).length - 1).isEqualTo(1);
    }

    @Test
    void enhance_escapesXssInAnchorText() {
        String html = "<p><a href=\"https://music.163.com/song?id=1\">"
            + "<script>alert(1)</script></a></p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).doesNotContain("<script>");
        // 文本节点转义后不应可执行
        assertThat(out).contains("data-rich-link");
    }

    @Test
    void enhance_blankOrNoHttp_unchanged() {
        assertThat(RichLinkEnhancer.enhance(null)).isNull();
        assertThat(RichLinkEnhancer.enhance("")).isEmpty();
        assertThat(RichLinkEnhancer.enhance("<p>你好</p>")).isEqualTo("<p>你好</p>");
    }

    @Test
    void platform_match_shortLinks() {
        assertThat(RichLinkPlatform.match("https://b23.tv/abcdef"))
            .contains(RichLinkPlatform.BILIBILI);
        assertThat(RichLinkPlatform.match("https://163cn.tv/xxx"))
            .contains(RichLinkPlatform.NETEASE);
        assertThat(RichLinkPlatform.match("https://youtu.be/dQw4w9WgXcQ"))
            .contains(RichLinkPlatform.YOUTUBE);
    }

    @Test
    void platform_match_rejectsNonHttpAndUnknownHost() {
        // match 只认音视频白名单
        assertThat(RichLinkPlatform.match("javascript:alert(1)")).isEmpty();
        assertThat(RichLinkPlatform.match("https://example.com")).isEmpty();
        assertThat(RichLinkPlatform.match("https://baike.baidu.com/item/x")).isEmpty();
    }

    @Test
    void platform_matchShare_genericFallback() {
        assertThat(RichLinkPlatform.matchShare("https://baike.baidu.com/item/x/1"))
            .contains(RichLinkPlatform.GENERIC);
        assertThat(RichLinkPlatform.matchShare("https://example.com/a"))
            .contains(RichLinkPlatform.GENERIC);
        // 白名单仍优先
        assertThat(RichLinkPlatform.matchShare("https://music.163.com/song?id=1"))
            .contains(RichLinkPlatform.NETEASE);
        assertThat(RichLinkPlatform.matchShare("javascript:alert(1)")).isEmpty();
    }

    @Test
    void platform_hostLabel_stripsWww() {
        assertThat(RichLinkPlatform.hostLabel("https://www.baike.baidu.com/item/x"))
            .isEqualTo("baike.baidu.com");
        assertThat(RichLinkPlatform.hostLabel("https://Example.COM/path"))
            .isEqualTo("example.com");
    }

    @Test
    void enhance_genericShareLink_becomesLinkCard() {
        String html = "<p><a href=\"https://baike.baidu.com/item/4%C2%B71%E4%B8%AD%E7%BE%8E%E5%8D%97%E6%B5%B7%E6%92%9E%E6%9C%BA%E4%BA%8B%E4%BB%B6/9658219\">"
            + "中美南海撞机事件</a></p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).contains("fireside-rich-link");
        assertThat(out).contains("fireside-rich-link--link");
        assertThat(out).contains("data-platform=\"generic\"");
        assertThat(out).contains("data-kind=\"link\"");
        assertThat(out).contains("baike.baidu.com");
        assertThat(out).contains("中美南海撞机事件");
        assertThat(out).contains("richlink/link.svg");
    }

    @Test
    void enhance_genericInlineInSentence_notUpgraded() {
        String html = "<p>参见<a href=\"https://baike.baidu.com/item/x\">词条</a>了解更多</p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).doesNotContain("fireside-rich-link");
        assertThat(out).contains("baike.baidu.com");
    }

    @Test
    void enhance_relativeHref_notUpgradedToGeneric() {
        // absUrl 依赖 baseUri=localhost；相对路径不得变成通用卡
        String html = "<p><a href=\"/about\">关于</a></p>";
        String out = RichLinkEnhancer.enhance(html);
        assertThat(out).doesNotContain("fireside-rich-link");
    }
}
