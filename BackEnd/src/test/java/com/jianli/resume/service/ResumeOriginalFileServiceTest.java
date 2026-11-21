package com.jianli.resume.service;

import com.jianli.common.BizException;
import com.jianli.config.ResumeStorageProperties;
import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeOriginalFileServiceTest {

    @TempDir
    Path tempDir;

    private ResumeVersionRepository repository;
    private ResumeOriginalFileService service;
    private ResumeVersion version;

    @BeforeEach
    void setUp() {
        repository = mock(ResumeVersionRepository.class);
        service = new ResumeOriginalFileService(repository, new ResumeStorageProperties(tempDir));

        version = new ResumeVersion();
        version.setId(21L);
        version.setResumeId(11L);
        when(repository.findOwnedVersion(21L, 7L)).thenReturn(Optional.of(version));
    }

    @Test
    void storesAndLoadsOriginalPdf() throws Exception {
        byte[] pdf = "%PDF-1.4\nresume".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "潘文杰-简历.pdf", "application/pdf", pdf);

        service.store(7L, 21L, file);
        ResumeOriginalFileService.StoredOriginal loaded = service.load(7L, 21L);

        assertNotNull(version.getOriginalFilePath());
        assertEquals("潘文杰-简历.pdf", loaded.filename());
        assertEquals(pdf.length, loaded.size());
        assertTrue(Files.readAllBytes(loaded.path()).length > 5);
    }

    @Test
    void rejectsFileThatIsNotPdf() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", "not a pdf".getBytes());

        assertThrows(BizException.class, () -> service.store(7L, 21L, file));
    }
}