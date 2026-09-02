package com.elderly.config;

/**
 * 自定义业务异常，携带错误码。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(500, message);
    }

    public int getCode() {
        return code;
    }
}
