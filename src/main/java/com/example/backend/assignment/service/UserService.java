package com.example.backend.assignment.service;

import com.example.backend.assignment.dto.*;
import com.example.backend.assignment.entity.*;
import com.example.backend.assignment.enums.AuthorType;
import com.example.backend.assignment.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UsersRepository usersRepository;
    private final BotRepository botRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final RedisService redisService;

    public ResponseUserDto signUp(RequestUserDto requestUserDto) {
        Users user = Users.builder()
            .username(requestUserDto.getUsername())
            .isPremium(requestUserDto.isPremium())
                .authorType(AuthorType.USER)
            .build();

        Optional<Users> exist=usersRepository.findByUsername(requestUserDto.getUsername());

        if(exist.isPresent()){
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");
        }

        usersRepository.save(user);

        return ResponseUserDto.builder()
            .username(user.getUsername())
            .userId(user.getId())
            .status("success")
            .build();
    }

    public ResponseBotDto createBot(RequestBotDto requestBotDto) {
        Bot bot = Bot.builder()
            .name(requestBotDto.getName())
            .personaDescription(requestBotDto.getPersonaDescription())
            .build();

        Optional<Bot> exist=botRepository.findByName(requestBotDto.getName());

        if(exist.isPresent()){
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Bot already exists");
        }

        botRepository.save(bot);

        return ResponseBotDto.builder()
            .botId(bot.getId())
            .name(bot.getName())
            .status("success")
            .build();
    }

    @Transactional
    public ResponsePostDto post(RequestPostDto requestPostDto) {
        Post postBuilder = Post.builder()
            .authorId(requestPostDto.getAuthorId())
            .authorType(requestPostDto.getAuthorType())
            .content(requestPostDto.getContent()).build();

        if (requestPostDto.getAuthorType() == AuthorType.USER) {

            Users user = usersRepository.findById(requestPostDto.getAuthorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
            postBuilder.setUser(user);
        } else {
            Bot bot = botRepository.findById(requestPostDto.getAuthorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found"));
            postBuilder.setBot(bot);
        }

        Post saved = postRepository.save(postBuilder);

        return ResponsePostDto.builder()
            .postId(saved.getId())
            .content(saved.getContent())
            .status("success")
            .build();
    }

    @Transactional
    public ResponseCommentDto addComment(String postId, RequestCommentDto req) {


        Post post = postRepository.findById(postId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found")
                );

        int depth = 1;
        String parentId = null;

        if (req.getParentId() != null && !req.getParentId().isBlank()) {
            Comment parent = commentRepository.findById(req.getParentId())
                    .orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent comment not found")
                    );

            if (!parent.getPostId().equals(postId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Parent comment does not belong to this post"
                );
            }

            depth = parent.getDepthLevel() + 1;
            parentId = parent.getId();

            if (depth > 20) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Max depth is 20"
                );
            }
        }

        boolean isBot = req.getAuthorType() == AuthorType.BOT;

         if (isBot) {
            if (req.getTargetUserId() == null || req.getTargetUserId().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "targetUserId is required for bot comments"
                );
            }

            redisService.runBotGuardrails(
                    postId,
                    req.getAuthorId(),
                    req.getTargetUserId()

            );
        }

         Comment comment = Comment.builder()
                .postId(postId)
                .authorId(req.getAuthorId())
                .authorType(req.getAuthorType())
                .content(req.getContent())
                .depthLevel(depth)
                .parentId(parentId)
                .build();

        Comment saved = commentRepository.save(comment);

         if (isBot) {
            redisService.incrementViralityScore(postId, 1);

            String botName = botRepository.findById(req.getAuthorId())
                    .map(Bot::getName)
                    .orElse("Bot#" + req.getAuthorId());

            redisService.handleNotification(
                    req.getTargetUserId(),
                    botName,
                    postId
            );
        } else {
            redisService.incrementViralityScore(postId, 50);
        }

         return ResponseCommentDto.builder()
                .commentId(saved.getId())
                .authorId(saved.getAuthorId())
                .content(saved.getContent())
                .depthLevel(saved.getDepthLevel())
                .status("success")
                .build();
    }


    @Transactional
    public void likePost(String postId, String userId) {
        postRepository.findById(postId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        usersRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        redisService.incrementViralityScore(postId, 20);
    }

}
