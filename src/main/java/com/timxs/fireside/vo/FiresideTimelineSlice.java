package com.timxs.fireside.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 游标分页的时间线切片（内置 /fireside 页使用）。
 * items 为当前页条目；hasNext 表示还有更早的动态，
 * 此时 nextCursor 为下一页游标（已编码，可直接作 ?before= 参数值）。
 */
@Data
@Builder
public class FiresideTimelineSlice {

    private List<FiresideMomentVo> items;

    private boolean hasNext;

    private String nextCursor;
}
