package com.example.backend.assignment.repository;

import com.example.backend.assignment.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.postId = :postId AND c.authorType = 'BOT'")
    long countBotCommentsByPostId(String postId);

}
