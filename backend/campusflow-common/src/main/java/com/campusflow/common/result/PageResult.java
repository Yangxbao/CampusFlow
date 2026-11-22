package com.campusflow.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页响应结构。
 */
@Data
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    /** 当前页数据 */
    private List<T> records;
    /** 总条数 */
    private long total;
    /** 页码（1 开始） */
    private long pageNum;
    /** 每页大小 */
    private long pageSize;

    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        return new PageResult<>(records, total, pageNum, pageSize);
    }
}
