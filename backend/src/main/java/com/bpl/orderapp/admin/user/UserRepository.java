package com.bpl.orderapp.admin.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    @Query("select u.email from User u "
         + "where u.role in ('SYS_ADMIN','ADMIN') and u.email is not null")
    List<String> findAdminEmails();

    @Query("select u.phoneNumber from User u "
         + "where u.role in ('SYS_ADMIN','ADMIN') and u.phoneNumber is not null")
    List<String> findAdminPhoneNumbers();

    @Query(value = "SELECT u.email FROM users u "
         + "JOIN user_application_assignments a ON a.user_id = u.id "
         + "WHERE a.application_id = :applicationId AND u.role = 'USER' "
         + "AND u.email IS NOT NULL", nativeQuery = true)
    List<String> findAssignedUserEmails(@Param("applicationId") Long applicationId);

    @Query(value = "SELECT u.phone_number FROM users u "
         + "JOIN user_application_assignments a ON a.user_id = u.id "
         + "WHERE a.application_id = :applicationId AND u.role = 'USER' "
         + "AND u.phone_number IS NOT NULL", nativeQuery = true)
    List<String> findAssignedUserPhoneNumbers(@Param("applicationId") Long applicationId);

    @Query(value = "SELECT uaa.application_id FROM user_application_assignments uaa "
         + "JOIN users u ON u.id = uaa.user_id "
         + "WHERE u.username = :username", nativeQuery = true)
    List<Long> findAssignedApplicationIds(@Param("username") String username);
}