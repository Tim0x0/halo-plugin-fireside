package com.timxs.fireside.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.rometools.rome.feed.synd.SyndEntryImpl;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * 发布时间回退链的行为验证：真实日期原样保留、无日期按 feed 位次错开、
 * 超容差的未来日期钳制到错开后的抓取时刻。
 */
class RssSyncServicePubDateTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void publishedDate_keptAsIs() {
        var entry = new SyndEntryImpl();
        Instant published = FETCHED_AT.minus(Duration.ofDays(3));
        entry.setPublishedDate(Date.from(published));
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 7)).isEqualTo(published);
    }

    @Test
    void updatedDate_usedWhenPublishedMissing() {
        var entry = new SyndEntryImpl();
        Instant updated = FETCHED_AT.minus(Duration.ofHours(2));
        entry.setUpdatedDate(Date.from(updated));
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 0)).isEqualTo(updated);
    }

    @Test
    void undatedEntries_spreadByFeedIndex_noTies() {
        // 无日期源整份 feed 不得共享同一时间戳——游标分页对同刻并列的兜底窗口
        // 只有 2*size 条，整源同刻会让更早的条目翻不到；按位次递减同时保留
        // feed 原始顺序（靠前的条目视为更新）
        var entry = new SyndEntryImpl();
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 0)).isEqualTo(FETCHED_AT);
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 1))
            .isEqualTo(FETCHED_AT.minusMillis(1));
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 19))
            .isEqualTo(FETCHED_AT.minusMillis(19));
    }

    @Test
    void farFutureDate_clampedToSpreadFallback() {
        var entry = new SyndEntryImpl();
        entry.setPublishedDate(Date.from(FETCHED_AT.plus(Duration.ofDays(2))));
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 5))
            .isEqualTo(FETCHED_AT.minusMillis(5));
    }

    @Test
    void nearFutureDate_withinTolerance_kept() {
        var entry = new SyndEntryImpl();
        Instant slightlyAhead = FETCHED_AT.plus(Duration.ofHours(6));
        entry.setPublishedDate(Date.from(slightlyAhead));
        assertThat(RssSyncService.resolvePubDate(entry, FETCHED_AT, 0))
            .isEqualTo(slightlyAhead);
    }
}
