package com.example.backend.assignment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseCommentDto {

    private String commentId;
    private String authorId;
    private String content;
    private String status;
    private int depthLevel;

}
