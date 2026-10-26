package com.timxs.fireside.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.timxs.fireside.extension.FriendMoment.MediaItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 内容指纹的稳定性：同样的清洗结果必须得到同样的哈希，否则未变更的条目会被每轮重写；
 * 任一展示字段变化（含媒体地址）必须得到不同哈希，否则源侧修改无法被同步。
 */
class RssSyncServiceContentHashTest {

    @Test
    void sameContent_sameHash() {
        String first = RssSyncService.contentHash("标题", "<p>正文</p>", "正文",
            List.of(photo("https://img.example.com/a.webp")));
        String second = RssSyncService.contentHash("标题", "<p>正文</p>", "正文",
            List.of(photo("https://img.example.com/a.webp")));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void mediaUrlChange_changesHash() {
        String before = RssSyncService.contentHash("标题", "<p>正文</p>", "正文",
            List.of(photo("https://img.example.com/a.webp")));
        String after = RssSyncService.contentHash("标题", "<p>正文</p>", "正文",
            List.of(photo("https://img.example.com/b.webp")));
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void mediaOrder_changesHash() {
        String forward = RssSyncService.contentHash("", "", "",
            List.of(photo("https://img.example.com/a.webp"),
                photo("https://img.example.com/b.webp")));
        String reversed = RssSyncService.contentHash("", "", "",
            List.of(photo("https://img.example.com/b.webp"),
                photo("https://img.example.com/a.webp")));
        assertThat(reversed).isNotEqualTo(forward);
    }

    @Test
    void fieldBoundary_notAmbiguous() {
        // 不加长度前缀时，"ab"+"c" 与 "a"+"bc" 会拼出同一串
        String first = RssSyncService.contentHash("ab", "c", "", List.of());
        String second = RssSyncService.contentHash("a", "bc", "", List.of());
        assertThat(second).isNotEqualTo(first);
    }

    private static MediaItem photo(String url) {
        var item = new MediaItem();
        item.setType("PHOTO");
        item.setUrl(url);
        return item;
    }
}
