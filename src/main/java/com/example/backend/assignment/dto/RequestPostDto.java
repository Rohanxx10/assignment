package com.example.backend.assignment.dto;

import com.example.backend.assignment.entity.Post;
import com.example.backend.assignment.enums.AuthorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestPostDto {

    private String authorId;
    private AuthorType authorType;
    private String content;

}
