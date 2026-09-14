package com.rabitah.backend.config;

import com.rabitah.backend.academic.StudentIdScheme;
import com.rabitah.backend.academic.StudentRoster;
import com.rabitah.backend.academic.StudentRosterRepository;
import com.rabitah.backend.user.AccountStatus;
import com.rabitah.backend.user.Role;
import com.rabitah.backend.user.User;
import com.rabitah.backend.user.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds the deterministic local-development roster; it never overwrites real student accounts. */
@Component
public class BaseDataSeeder implements ApplicationRunner {
    private static final List<DepartmentSeed> DEPARTMENTS = List.of(
            new DepartmentSeed("MPE", "Mechanical and Production Engineering"),
            new DepartmentSeed("EEE", "Electrical and Electronic Engineering"),
            new DepartmentSeed("CSE", "Computer Science and Engineering"),
            new DepartmentSeed("CEE", "Civil Engineering"));
    // This is a development cohort-to-year mapping. Batch is retained in the ID itself.
    private static final List<Cohort> COHORTS = List.of(
            new Cohort(21, 4), new Cohort(22, 3), new Cohort(23, 2), new Cohort(24, 1));

    private final StudentRosterRepository roster;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final int studentsPerSection;
    private final String adminPassword;
    private final String demoPassword;
    private final boolean seedDemoStudentAccounts;
    private final Path credentialExport;

    public BaseDataSeeder(StudentRosterRepository roster, UserRepository users, PasswordEncoder passwords,
                          JdbcTemplate jdbc,
                          @Value("${rabitah.seed.students-per-section-year:60}") int studentsPerSection,
                          @Value("${RABITAH_SYSTEM_ADMIN_PASSWORD:}") String adminPassword,
                          @Value("${RABITAH_DEMO_PASSWORD:}") String demoPassword,
                          @Value("${rabitah.seed.demo-student-accounts:false}") boolean seedDemoStudentAccounts,
                          @Value("${rabitah.seed.credentials-export:./generated/demo-student-credentials.csv}") String credentialExport) {
        this.roster = roster;
        this.users = users;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.studentsPerSection = studentsPerSection;
        this.adminPassword = adminPassword;
        this.demoPassword = demoPassword;
        this.seedDemoStudentAccounts = seedDemoStudentAccounts;
        this.credentialExport = Path.of(credentialExport);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (studentsPerSection < StudentIdScheme.MIN_ROLL || studentsPerSection > StudentIdScheme.MAX_ROLL) {
            throw new IllegalStateException("RABITAH_STUDENTS_PER_SECTION_YEAR must be between 1 and 60.");
        }
        seedDepartments();
        seedAdministrator();
        seedRoster();
        seedCommunityRooms();
        seedCourses();
        if (seedDemoStudentAccounts) {
            seedDemoStudentAccounts();
        }
        if (!demoPassword.isBlank()) {
            seedDemoContent();
        }
    }

    private void seedDepartments() {
        for (DepartmentSeed department : DEPARTMENTS) {
            jdbc.update("insert into departments(code,name) values (?,?) on conflict do nothing",
                    department.code(), department.name());
            for (int section = 1; section <= 2; section++) {
                jdbc.update("insert into department_sections(department_code,section_code) values (?,?) on conflict do nothing",
                        department.code(), StudentIdScheme.sectionCode(section));
            }
            jdbc.update("insert into academic_programs(department_code,name) values (?,?) on conflict do nothing",
                    department.code(), "BSc in " + department.name());
        }
    }

    private void seedAdministrator() {
        if (users.findByLoginIdIgnoreCase("SYSADMIN").isEmpty()) {
            if (adminPassword.length() < 8) {
                throw new IllegalStateException("RABITAH_SYSTEM_ADMIN_PASSWORD must be configured with at least 8 characters");
            }
            users.save(new User("SYSADMIN", null, null, "System Admin", passwords.encode(adminPassword),
                    Role.SYSTEM_ADMIN, AccountStatus.ACTIVE, null, null, null));
        }
    }

    private void seedRoster() {
        for (Cohort cohort : COHORTS) {
            for (DepartmentSeed department : DEPARTMENTS) {
                for (int section = 1; section <= 2; section++) {
                    for (int roll = StudentIdScheme.MIN_ROLL; roll <= studentsPerSection; roll++) {
                        String studentId = StudentIdScheme.format(cohort.batch(), department.code(), section, roll);
                        if (roster.findByStudentIdIgnoreCase(studentId).isEmpty()) {
                            String sectionCode = StudentIdScheme.sectionCode(section);
                            roster.save(new StudentRoster(studentId, "Student " + studentId, department.code(),
                                    sectionCode, cohort.academicYear()));
                        }
                    }
                }
            }
        }
    }

    private void seedCommunityRooms() {
        for (DepartmentSeed department : DEPARTMENTS) {
            jdbc.update("""
                    insert into community_rooms(id,department_code,section_code,academic_year,name,room_type)
                    values (gen_random_uuid(),?, '*', 0, ?, 'DEPARTMENT')
                    on conflict(department_code,section_code,academic_year)
                    do update set name=excluded.name, room_type='DEPARTMENT'
                    """, department.code(), department.code() + " Department Community");
            for (int year = 1; year <= 4; year++) {
                for (int section = 1; section <= 2; section++) {
                    String sectionCode = StudentIdScheme.sectionCode(section);
                    jdbc.update("""
                            insert into community_rooms(id,department_code,section_code,academic_year,name,room_type)
                            values (gen_random_uuid(),?,?,?,?, 'SECTION')
                            on conflict(department_code,section_code,academic_year) do nothing
                            """, department.code(), sectionCode, year,
                            department.code() + " Year " + year + " Section " + sectionCode);
                }
            }
        }
    }

    private void seedCourses() {
        for (DepartmentSeed department : DEPARTMENTS) {
            java.util.UUID program = jdbc.queryForObject(
                    "select id from academic_programs where department_code=? limit 1", java.util.UUID.class,
                    department.code());
            for (int year = 1; year <= 4; year++) {
                jdbc.update("""
                        insert into courses(department_code,program_id,course_code,course_name,academic_year,semester)
                        values(?,?,?,?,?,?) on conflict do nothing
                        """, department.code(), program, department.code() + year + "01",
                        department.code() + " Year " + year, year, 1);
            }
        }
    }

    /**
     * Optional local-only convenience. It creates active demo users and writes credentials only
     * for accounts created during this run, never resetting an existing student's password.
     */
    private void seedDemoStudentAccounts() {
        List<String> rows = roster.findAll().stream()
                .filter(student -> StudentIdScheme.parse(student.getStudentId()).isPresent())
                .sorted(Comparator.comparing(StudentRoster::getStudentId))
                .map(this::seedDemoStudentAccount)
                .filter(java.util.Objects::nonNull)
                .toList();
        exportCredentials(rows);
    }

    private String seedDemoStudentAccount(StudentRoster student) {
        if (users.findByLoginIdIgnoreCase(student.getStudentId()).isPresent()) {
            return null;
        }
        StudentIdScheme.Parts parts = StudentIdScheme.parse(student.getStudentId()).orElseThrow();
        String password = temporaryPassword(student.getStudentId());
        users.save(new User(student.getStudentId(), student.getStudentId(), student.getLegalName(),
                "Student " + student.getStudentId(), passwords.encode(password), Role.STUDENT,
                AccountStatus.ACTIVE, student.getDepartmentCode(), student.getSectionCode(), student.getCurrentYear()));
        return String.join(",", student.getStudentId(), password, String.valueOf(parts.batch()),
                student.getDepartmentCode(), student.getSectionCode(), String.valueOf(student.getCurrentYear()));
    }

    private void exportCredentials(List<String> rows) {
        try {
            Path absolute = credentialExport.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String csv = "student_id,temporary_password,batch,department,section,academic_year\n"
                    + String.join("\n", rows) + (rows.isEmpty() ? "" : "\n");
            Files.writeString(absolute, csv, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Could not write the local demo credential export.", error);
        }
    }

    private void seedDemoContent() {
        String cse = StudentIdScheme.format(24, "CSE", 1, 3);
        String eee = StudentIdScheme.format(23, "EEE", 2, 3);
        String cee = StudentIdScheme.format(22, "CEE", 1, 3);
        seedUser(cse, "Campus Coder");
        seedUser(eee, "Circuit Sage");
        seedUser(cee, "Civil Scholar");
        User admin = users.findByLoginIdIgnoreCase("SYSADMIN").orElseThrow();
        if (jdbc.queryForObject("select count(*) from posts", Long.class) == 0) {
            for (int index = 1; index <= 6; index++) {
                jdbc.update("""
                        insert into posts(id,author_id,body,visibility,status,department_code,section_code,academic_year,created_at,updated_at)
                        values(gen_random_uuid(),?,?,?,'APPROVED',null,null,null,now()-?*interval '1 hour',now())
                        """, admin.getId(), "Welcome to Rabitah campus update " + index, "PUBLIC", index);
            }
            for (DepartmentSeed department : DEPARTMENTS) {
                jdbc.update("""
                        insert into notices(id,department_code,title,body,published_by,notice_type,published_at,updated_at)
                        values(gen_random_uuid(),?,?,?,?,?,now(),now())
                        """, department.code(), department.code() + " Academic Notice",
                        "Official academic information for " + department.code() + " students.", admin.getId(), "ACADEMIC");
            }
        }
        for (String studentId : List.of(cse, eee, cee)) {
            User user = users.findByLoginIdIgnoreCase(studentId).orElseThrow();
            java.util.UUID room = jdbc.queryForObject("""
                    select id from community_rooms where department_code=? and section_code=? and academic_year=?
                    """, java.util.UUID.class, user.getDepartmentCode(), user.getSectionCode(), user.getAcademicYear());
            jdbc.update("""
                    insert into community_messages(room_id,author_id,body)
                    select ?,?,? where not exists(select 1 from community_messages where room_id=?)
                    """, room, admin.getId(), "Welcome to your official community room.", room);
        }
    }

    private void seedUser(String studentId, String nickname) {
        if (users.findByLoginIdIgnoreCase(studentId).isPresent()) {
            return;
        }
        StudentRoster student = roster.findByStudentIdIgnoreCase(studentId).orElseThrow();
        users.saveAndFlush(new User(studentId, studentId, student.getLegalName(), nickname,
                passwords.encode(demoPassword), Role.STUDENT, AccountStatus.ACTIVE, student.getDepartmentCode(),
                student.getSectionCode(), student.getCurrentYear()));
    }

    private String temporaryPassword(String studentId) {
        return "Rbt!" + studentId;
    }

    private record DepartmentSeed(String code, String name) {}

    private record Cohort(int batch, int academicYear) {}
}
