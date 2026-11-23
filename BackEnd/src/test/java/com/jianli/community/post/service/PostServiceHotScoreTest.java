package com.jianli.community.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.analysis.repository.AnalysisReportRepository;
import com.jianli.community.hot.HotScoreService;
import com.jianli.community.post.dto.PostVO;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import com.jianli.community.vote.repository.VoteRepository;
import com.jianli.jd.repository.JobDescriptionRepository;
import com.jianli.privacy.service.PrivacyReviewService;
import com.jianli.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceHotScoreTest {

    @Test
    void detailUpdatesViewAndHotScoreImmediately() {
        PostRepository postRepository = mock(PostRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        HotScoreService hotScoreService = mock(HotScoreService.class);
        PostService service = new PostService(
                postRepository,
                mock(AnalysisReportRepository.class),
                mock(JobDescriptionRepository.class),
                mock(PrivacyReviewService.class),
                mock(VoteRepository.class),
                userRepository,
                new ObjectMapper(),
                hotScoreService
        );

        Post post = new Post();
        post.setId(1L);
        post.setUserId(2L);
        post.setTitle("测试帖子");
        post.setStatus("PUBLISHED");

        when(postRepository.findByIdAndStatus(1L, "PUBLISHED"))
                .thenReturn(Optional.of(post));
        when(userRepository.findById(2L)).thenReturn(Optional.empty());
        when(hotScoreService.compute(post)).thenReturn(12.5);

        PostVO result = service.detail(1L, null);

        assertNotNull(result);
        assertEquals(1, post.getViewCount());
        assertEquals(12.5, post.getHotScore());
        verify(postRepository).save(post);
        verify(hotScoreService).updateZset(1L, 12.5);
    }
}
