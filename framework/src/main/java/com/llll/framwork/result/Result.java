package com.llll.framwork.result;

/**
 * Controller 的统一 JSON 返回格式。
 * code 为业务状态码，不代替 HTTP 状态码。
 *
 * @param <T> 返回数据的类型
 */
public final class Result<T> {

    public static final String SUCCESS_CODE = "0";

    private final String code;
    private final String message;
    private final T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "success", data);
    }

    public static <T> Result<T> fail(String code, String message) {
        return fail(code, message, null);
    }

    public static <T> Result<T> fail(String code, String message, T data) {
        return new Result<>(code, message, data);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
