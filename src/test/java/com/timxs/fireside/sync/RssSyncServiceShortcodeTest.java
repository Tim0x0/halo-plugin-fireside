package com.timxs.fireside.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * 短代码剥除的行为验证：成对删除、孤立保留、code/pre 免疫。
 */
class RssSyncServiceShortcodeTest {

    private String strip(String html) {
        Document doc = Jsoup.parse(html, "https://example.com/");
        RssSyncService.stripShortcodes(doc);
        return doc.body().text();
    }

    @Test
    void typechoPairedMarkers_removed() {
        // 真实案例：Typecho 主题相册短代码，标记与图片同段、被 <br> 分隔
        String html = "<p>最近很懒散，好久没做练习了</p>"
            + "<p>{cat_gallery}<br><img src=\"https://img.example.com/a.png\">"
            + "<br><img src=\"https://img.example.com/b.png\"><br>{/cat_gallery}</p>";
        String text = strip(html);
        assertThat(text).doesNotContain("{cat_gallery}");
        assertThat(text).doesNotContain("{/cat_gallery}");
        assertThat(text).contains("最近很懒散");
    }

    @Test
    void typechoMarkerWithAttributes_removed() {
        String text = strip("<p>{collapse title=\"点开看\"}藏起来的话{/collapse}</p>");
        assertThat(text).isEqualTo("藏起来的话");
    }

    @Test
    void unpairedBraceToken_kept() {
        String text = strip("<p>配置里写 {config} 就可以生效</p>");
        assertThat(text).contains("{config}");
    }

    @Test
    void wordPressPairedBrackets_removed_isolatedBracketsKept() {
        String text = strip("<p>[caption width=\"300\"]图片说明[/caption] [转载] 一篇文章 [1]</p>");
        assertThat(text).doesNotContain("[caption");
        assertThat(text).doesNotContain("[/caption]");
        assertThat(text).contains("图片说明");
        assertThat(text).contains("[转载]");
        assertThat(text).contains("[1]");
    }

    @Test
    void liquidPairedTags_removed() {
        String text = strip("<p>{% codeblock lang:js %}正文{% endcodeblock %}</p>");
        assertThat(text).isEqualTo("正文");
    }

    @Test
    void hugoPairedShortcode_removed() {
        String text = strip("<p>{{< gallery >}}看图{{< /gallery >}}</p>");
        assertThat(text).isEqualTo("看图");
    }

    @Test
    void codeBlockContent_untouched() {
        // 代码块里哪怕出现成对短代码格式的内容，也不参与配对、不被删除
        String html = "<p>示例：</p>"
            + "<pre><code>{gallery}...{/gallery}\n[b]加粗[/b]</code></pre>";
        String text = strip(html);
        assertThat(text).contains("{gallery}");
        assertThat(text).contains("{/gallery}");
        assertThat(text).contains("[b]");
    }

    @Test
    void pairingScope_excludesCodeBlocks() {
        // 起始标记在正文、闭合只在代码块里 → 不构成配对，正文标记保留
        String html = "<p>{spoiler} 这里讲用法</p>"
            + "<pre><code>{/spoiler}</code></pre>";
        String text = strip(html);
        assertThat(text).contains("{spoiler}");
    }

    @Test
    void markersInSeparateParagraphs_stillPaired() {
        String html = "<p>{hidden}</p><p>中间内容</p><p>{/hidden}</p>";
        String text = strip(html);
        assertThat(text).doesNotContain("{hidden}");
        assertThat(text).doesNotContain("{/hidden}");
        assertThat(text).contains("中间内容");
    }

    @Test
    void jsonAndTemplateVariables_untouched() {
        String text = strip("<p>返回 { \"key\": 1 }，模板里用 ${var} 取值</p>");
        assertThat(text).contains("{ \"key\": 1 }");
        assertThat(text).contains("${var}");
    }
}
