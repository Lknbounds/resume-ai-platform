package com.jianli.community.adopt.service;

import com.jianli.analysis.entity.AnalysisReport;
import com.jianli.analysis.repository.AnalysisReportRepository;
import com.jianli.analysis.service.AnalysisService;
import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.community.adopt.dto.AdoptRequest;
import com.jianli.community.adopt.dto.AdoptResultVO;
import com.jianli.community.comment.dto.CommentVO;
import com.jianli.community.comment.entity.Comment;
import com.jianli.community.comment.repository.CommentRepository;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import com.jianli.resume.dto.AddVersionRequest;
import com.jianli.resume.dto.VersionDetailVO;
import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.enums.VersionSource;
import com.jianli.resume.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 把“社区建议”变成“简历新版本”的完整闭环。
 *
 * 流程：
 *   评论 -> 校验帖子作者身份 -> 标记 adopted
 *        -> 找到帖子来源报告 -> 来源版本所属简历
 *        -> 新增 ADOPT_COMMENT 版本 -> 自动重新分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdoptService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final AnalysisReportRepository reportRepository;
    private final ResumeService resumeService;
    private final AnalysisService analysisService;

    @Transactional
    public AdoptResultVO adopt(Long userId, Long commentId, AdoptRequest req) {
        Comment comment = commentRepository.findByIdAndStatus(commentId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.COMMENT_NOT_FOUND));

        Post post = postRepository.findByIdAndStatus(comment.getPostId(), "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.POST_NOT_VISIBLE));

        if (!post.getUserId().equals(userId) || comment.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ADOPT_FORBIDDEN);
        }

        // 重复点击只返回已有结果，避免重复生成版本。
        if (Boolean.TRUE.equals(comment.getAdopted())) {
            return existingResult(userId, comment);
        }

        comment.setAdopted(true);
        comment.setAdoptedAt(LocalDateTime.now(ZoneOffset.UTC));

        String updatedContent = req == null ? null : trimToNull(req.updatedResumeContent());
        Long newVersionId = null;
        Integer versionNo = null;
        Long resumeId = null;
        Long analysisReportId = null;

        if (updatedContent != null) {
            AnalysisReport report = post.getReportId() == null ? null
                    : reportRepository.findById(post.getReportId()).orElse(null);

            /*
             * 帖子快照永远保留，但来源报告可能已被用户删除。
             * 这时仍允许“仅标记采纳”，不做虚假版本回写。
             */
            if (report != null) {
                ResumeVersion sourceVersion = resumeService.requireOwnedVersion(
                        userId, report.getResumeVersionId());
                resumeId = sourceVersion.getResumeId();

                String changeNote = req == null ? null : trimToNull(req.changeNote());
                if (changeNote == null) {
                    changeNote = "采纳社区建议 · "
                            + CommentVO.sectionLabelOf(comment.getSection());
                }

                VersionDetailVO version = resumeService.addVersion(userId, resumeId,
                        new AddVersionRequest(
                                updatedContent,
                                changeNote,
                                VersionSource.ADOPT_COMMENT,
                                null
                        ));

                newVersionId = version.id();
                versionNo = version.versionNo();
                comment.setAdoptedVersionId(newVersionId);

                // 当前规则分析是同步的：新报告与版本在同一事务内落库。
                var analysis = analysisService.create(
                        userId, newVersionId, report.getJdId(), true);
                analysisReportId = analysis.id();

                log.info("社区建议已采纳并生成简历版本, commentId={}, resumeId={}, versionId={}, reportId={}",
                        commentId, resumeId, newVersionId, analysisReportId);
            } else {
                log.info("来源报告已删除，仅标记采纳, commentId={}", commentId);
            }
        }

        commentRepository.save(comment);
        return new AdoptResultVO(commentId, true, newVersionId, versionNo,
                resumeId, analysisReportId);
    }

    private AdoptResultVO existingResult(Long userId, Comment comment) {
        Long versionId = comment.getAdoptedVersionId();
        if (versionId == null) {
            return new AdoptResultVO(comment.getId(), true, null, null, null, null);
        }

        VersionDetailVO version = resumeService.getVersion(userId, versionId);
        return new AdoptResultVO(comment.getId(), true, version.id(), version.versionNo(),
                version.resumeId(), null);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
