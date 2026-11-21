package com.jianli.privacy.repository;

import com.jianli.privacy.entity.PrivacyReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  审核记录的数据访问。
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface PrivacyReviewRepository extends JpaRepository<PrivacyReview, Long> {

    /** 我的审核记录（用于"我曾经被拒过几次"这种展示） */
    List<PrivacyReview> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 某个场景下的审核记录（规则调优时统计用） */
    List<PrivacyReview> findBySceneAndStatus(String scene, String status);
}
