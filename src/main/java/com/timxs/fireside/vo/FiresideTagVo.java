package com.timxs.fireside.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 标签项，字段与瞬间插件的 MomentTagVo 一致（name/momentCount/permalink），
 * 主题现有的标签栏循环可直接消费。
 */
@Data
@Builder
public class FiresideTagVo {

    private String name;

    private Integer momentCount;

    private String permalink;
}
