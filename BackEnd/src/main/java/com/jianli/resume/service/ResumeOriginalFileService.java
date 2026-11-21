package com.jianli.resume.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.config.ResumeStorageProperties;
import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.repository.ResumeVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 保存和读取简历原始 PDF。文件放在后端私有目录，不暴露为静态资源。
 */
@Service
@RequiredArgsConstructor
public class ResumeOriginalFileService {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final ResumeVersionRepository versionRepository;
    private final ResumeStorageProperties storageProperties;

    @Transactional
    public void store(Long userId, Long versionId, MultipartFile file) throws IOException {
        ResumeVersion version = ownedVersion(userId, versionId);
        byte[] bytes = file == null ? new byte[0] : file.getBytes();
        validatePdf(file, bytes);

        Path root = storageProperties.root().toAbsolutePath().normalize();
        String relativePath = userId + "/" + version.getResumeId() + "/" + versionId + "-original.pdf";
        Path target = root.resolve(relativePath).normalize();

        if (!target.startsWith(root)) {
            throw new BizException(ErrorCode.PARAM_INVALID, null, "非法的文件路径");
        }

        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".resume-original-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        String filename = file.getOriginalFilename() == null
                ? "original.pdf"
                : Path.of(file.getOriginalFilename()).getFileName().toString();

        version.setOriginalFilePath(relativePath);
        version.setOriginalFileName(filename);
        version.setOriginalMimeType("application/pdf");
        version.setOriginalFileSize((long) bytes.length);
        version.setOriginalFileHash(sha256(bytes));
        version.setOriginalUploadedAt(LocalDateTime.now());
        versionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public StoredOriginal load(Long userId, Long versionId) {
        ResumeVersion version = ownedVersion(userId, versionId);
        if (version.getOriginalFilePath() == null || version.getOriginalFilePath().isBlank()) {
            throw new BizException(ErrorCode.RESUME_ORIGINAL_NOT_FOUND);
        }

        Path root = storageProperties.root().toAbsolutePath().normalize();
        Path path = root.resolve(version.getOriginalFilePath()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new BizException(ErrorCode.RESUME_ORIGINAL_NOT_FOUND);
        }

        return new StoredOriginal(
                path,
                version.getOriginalFileName() == null ? "original.pdf" : version.getOriginalFileName(),
                "application/pdf",
                version.getOriginalFileSize() == null ? path.toFile().length() : version.getOriginalFileSize()
        );
    }

    @Transactional
    public void deleteForResume(Long resumeId) {
        Path root = storageProperties.root().toAbsolutePath().normalize();
        for (ResumeVersion version : versionRepository.findByResumeIdOrderByVersionNoDesc(resumeId)) {
            if (version.getOriginalFilePath() == null) {
                continue;
            }

            Path path = root.resolve(version.getOriginalFilePath()).normalize();
            if (path.startsWith(root)) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    // 文件清理失败不影响数据库删除，后续可由运维任务补清。
                }
            }
        }
    }

    private ResumeVersion ownedVersion(Long userId, Long versionId) {
        return versionRepository.findOwnedVersion(versionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.VERSION_NOT_FOUND));
    }

    private void validatePdf(MultipartFile file, byte[] bytes) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, null, "请选择 PDF 原件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(ErrorCode.PAYLOAD_TOO_LARGE, null, "PDF 原件不能超过 5MB");
        }

        String filename = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".pdf")) {
            throw new BizException(ErrorCode.PARAM_INVALID, null, "原件预览仅支持 PDF 文件");
        }

        String header = bytes.length < 5
                ? ""
                : new String(bytes, 0, 5, StandardCharsets.US_ASCII);
        if (!"%PDF-".equals(header)) {
            throw new BizException(ErrorCode.PARAM_INVALID, null, "文件不是有效的 PDF");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 算法不可用", ex);
        }
    }

    public record StoredOriginal(Path path, String filename, String contentType, long size) {
    }
}