package com.example.backend.assignment.controller;

import com.example.backend.assignment.dto.*;
import com.example.backend.assignment.service.RedisService;
import com.example.backend.assignment.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RedisService redisService;

    @PostMapping("/user")
    public ResponseEntity<ResponseUserDto> signUp(@RequestBody RequestUserDto requestUserDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.signUp(requestUserDto));
    }

    @PostMapping("/bot")
    public ResponseEntity<ResponseBotDto> createBot(@RequestBody RequestBotDto requestBotDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createBot(requestBotDto));
    }

    @PostMapping("/post")
    public ResponseEntity<ResponsePostDto> createPost(@RequestBody RequestPostDto requestPostDto) {
        System.out.println(requestPostDto.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.post(requestPostDto));
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ResponseCommentDto> addComment(
            @PathVariable String postId,
            @RequestBody RequestCommentDto requestCommentDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.addComment(postId, requestCommentDto));
    }

    @PostMapping("/posts/{postId}/like")
    public ResponseEntity<Map<String, Object>> likePost(
            @PathVariable String postId,
            @RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        userService.likePost(postId, userId);
        return ResponseEntity.ok(Map.of(
            "postId", postId,
            "viralityScore", redisService.getViralityScore(postId),
            "status", "success"
        ));
    }

    @GetMapping("/posts/{postId}/stats")
    public ResponseEntity<Map<String, Object>> getPostStats(@PathVariable String postId) {
        return ResponseEntity.ok(Map.of(
            "postId", postId,
            "viralityScore", redisService.getViralityScore(postId),
            "botCount", redisService.getBotCount(postId),
            "botCap", 100
        ));
    }

}
