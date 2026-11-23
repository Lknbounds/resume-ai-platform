package com.jianli.community.comment.service;

import com.jianli.community.comment.entity.Comment;
import com.jianli.community.comment.repository.CommentRepository;
import com.jianli.community.hot.HotScoreService;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import com.jianli.community.vote.repository.VoteRepository;
import com.jianli.privacy.service.PrivacyReviewService;
import com.jianli.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentServiceHotScoreTest {

    @Test
    void deletingCommentUpdatesPostHotScoreImmediately() {
        CommentRepository commentRepository = mock(CommentRepository.class);
        PostRepository postRepository = mock(PostRepository.class);
        HotScoreService hotScoreService = mock(HotScoreService.class);
        CommentService service = new CommentService(
                commentRepository,
                postRepository,
                mock(VoteRepository.class),
                mock(UserRepository.class),
                mock(PrivacyReviewService.class),
                hotScoreService
        );

        Comment comment = new Comment();
        comment.setId(10L);
        comment.setPostId(20L);
        comment.setUserId(30L);
        comment.setStatus("PUBLISHED");

        Post post = new Post();
        post.setId(20L);
        post.setUserId(40L);
        post.setStatus("PUBLISHED");
        post.setCommentCount(3);

        when(commentRepository.findByIdAndStatus(10L, "PUBLISHED"))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(20L)).thenReturn(Optional.of(post));
        when(hotScoreService.compute(post)).thenReturn(6.25);

        service.delete(30L, 10L);

        assertEquals("DELETED", comment.getStatus());
        assertEquals(2, post.getCommentCount());
        assertEquals(6.25, post.getHotScore());
        verify(postRepository).save(post);
        verify(hotScoreService).updateZset(20L, 6.25);
    }
}
