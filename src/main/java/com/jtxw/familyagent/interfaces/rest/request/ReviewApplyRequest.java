package com.jtxw.familyagent.interfaces.rest.request;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:41
 * @Description:
 */

@Schema(description = "人工复核应用请求")
public class ReviewApplyRequest {
    /**
     * 复核动作，取值 include 或 exclude
     */
    @Schema(description = "复核动作，include 表示纳入统计，exclude 表示排除统计", example = "include", allowableValues = {"include", "exclude"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String action;
    /**
     * 复核备注
     */
    @Schema(description = "人工复核备注", example = "确认是正常家庭消耗品购买记录")
    private String note;

    public ReviewApplyRequest() {
    }

    public String action() {
        return action;
    }

    public String note() {
        return note;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
