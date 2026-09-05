package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "relatives")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Relative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "nickname", length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", nullable = false, length = 20)
    private GroupType groupType;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * false = phần NĂM của dateOfBirth chỉ là giá trị đại diện (mobile gửi
     * 1900) do người dùng không nhớ rõ năm sinh — chỉ Tháng/Ngày đáng tin,
     * không dùng để tính tuổi (RelativeService.getDetail). Không ảnh hưởng
     * việc tính "sinh nhật lần tới" (RelativeService.nextBirthdayOccurrence
     * vốn chỉ dùng tháng/ngày). true = năm sinh thật, dữ liệu cũ mặc định
     * true (V23_20260906_add_relative_dob_year_known.sql).
     */
    @Column(name = "date_of_birth_year_known", nullable = false)
    @Builder.Default
    private Boolean dateOfBirthYearKnown = true;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    @Column(name = "weight_kg", precision = 5, scale = 1)
    private BigDecimal weightKg;

    /** JSON array string, VD: ["đọc sách","nấu ăn"] — parse ở Service */
    @Column(name = "hobbies", columnDefinition = "TEXT")
    private String hobbies;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "total_events")
    @Builder.Default
    private Integer totalEvents = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * GIA_DINH/CON_CAI/BAN_BE là nhóm cũ, giữ lại CHỈ để không vỡ dữ liệu cũ
     * (KHÔNG còn hiện trong picker "Quan hệ" mới — xem
     * {@code relative_form_screen.dart}). Danh sách chọn mới (theo ảnh mẫu
     * "Quan hệ với bạn"): BAN_THAN, ONG, BA, BO, ME, VO_CHONG, ANH_CHI_EM,
     * CON, NGUOI_YEU, NGUOI_THAN.
     */
    public enum GroupType {
        GIA_DINH, VO_CHONG, CON_CAI, BAN_BE, ANH_CHI_EM,
        BAN_THAN, ONG, BA, BO, ME, CON, NGUOI_YEU, NGUOI_THAN
    }

    public enum Gender {
        MALE, FEMALE, OTHER
    }
}
