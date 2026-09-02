package com.elderly.common;

import lombok.Data;

/**
 * 统一API响应包装类。
 */
@Data
public class R<T> {
    private Integer code;
    private String msg;
    private T data;

    public static <T> R<T> success() {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = "操作成功";
        return r;
    }

    public static <T> R<T> success(String msg) {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = msg;
        return r;
    }

    public static <T> R<T> success(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = "操作成功";
        r.data = data;
        return r;
    }

    public static <T> R<T> success(String msg, T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = msg;
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(String msg) {
        R<T> r = new R<>();
        r.code = 500;
        r.msg = msg;
        return r;
    }

    public static <T> R<T> fail(Integer code, String msg) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        return r;
    }
}
