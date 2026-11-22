package com.bpl.orderapp.admin.log;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ApplicationLogLineRepository extends JpaRepository<ApplicationLogLine, Long> {

    /** The newest stored line; its number is the current maximum for the application. */
    Optional<ApplicationLogLine> findFirstByApplicationIdOrderByLineNumberDesc(Long applicationId);

    List<ApplicationLogLine> findByApplicationIdOrderByLineNumberAsc(Long applicationId);

    /** Retention trim: drops every line numbered {@code maxLineNumber} or lower. */
    @Modifying
    @Transactional
    @Query("delete from ApplicationLogLine l "
         + "where l.applicationId = :applicationId and l.lineNumber <= :maxLineNumber")
    int deleteUpToLine(@Param("applicationId") Long applicationId,
                       @Param("maxLineNumber") long maxLineNumber);
}
