package com.timxs.fireside.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 主题页 404。Halo 核心的同名异常在 application 模块，插件 API 不可见，
 * 故自备同形态子类（与瞬间插件一致）。HaloErrorWebExceptionHandler 按 HTTP 状态
 * 渲染主题 error 页，不依赖核心那个具体类型。
 */
public class NotFoundException extends ResponseStatusException {

    public NotFoundException(String reason) {
        super(HttpStatus.NOT_FOUND, reason);
    }
}
