package com.example.backend.assignment.entity;

import com.example.backend.assignment.enums.AuthorType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // The post this comment belongs to
    @Column(name = "post_id", nullable = false)
    private String postId;

    // Who wrote the comment (user or bot)
    @Column(name = "author_id", nullable = false)
    private String authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false)
    private AuthorType authorType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // Parent comment (for nested comments)
    @Column(name = "parent_id")
    private String parentId;

    // Optional: for bot comments targeting a specific user
    @Column(name = "target_user_id")
    private String targetUserId;

    // Depth of nested comment (root comment = 0)
    @Column(name = "depth_level")
    private int depthLevel;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}