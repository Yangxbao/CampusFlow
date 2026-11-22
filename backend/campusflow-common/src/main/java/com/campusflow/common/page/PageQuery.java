package com.campusflow.common.page;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用分页查询参数。
 */
@Data
public class PageQuery implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private long pageNum = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private long pageSize = 10;

    public long offset() {
        return (pageNum - 1) * pageSize;
    }
}
