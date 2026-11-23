package com.corebank.common;

import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class PageParams {

    public static final int MAX_SIZE = 100;

    private PageParams() {
    }

    /** page >= 0, 1 <= size <= 100 이 아니면 INVALID_INPUT. */
    public static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 는 0 이상, size 는 1~" + MAX_SIZE + " 이어야 합니다.");
        }
    }

    /** 최신순 (createdAt DESC, id DESC). */
    public static PageRequest newestFirst(int page, int size) {
        validate(page, size);
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
