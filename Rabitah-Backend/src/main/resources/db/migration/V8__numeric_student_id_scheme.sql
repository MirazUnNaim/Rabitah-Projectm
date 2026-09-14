-- Convert the deterministic legacy IDs (for example CSE1A001) to BB00DDSRR.
-- Existing real records that already use a different convention are deliberately left untouched.
WITH legacy AS (
    SELECT id, student_id,
           regexp_match(student_id, '^([A-Z]+)([1-4])([AB])([0-9]{3})$') AS code
    FROM student_roster
), translated AS (
    SELECT id,
           CASE code[2]
               WHEN '1' THEN '24'
               WHEN '2' THEN '23'
               WHEN '3' THEN '22'
               WHEN '4' THEN '21'
           END
           || '00'
           || CASE code[1]
               WHEN 'MPE' THEN '11'
               WHEN 'EEE' THEN '21'
               WHEN 'CSE' THEN '41'
               WHEN 'CEE' THEN '51'
           END
           || CASE code[3] WHEN 'A' THEN '1' WHEN 'B' THEN '2' END
           || lpad((code[4]::integer)::text, 2, '0') AS new_student_id
    FROM legacy
    WHERE code IS NOT NULL
      AND code[1] IN ('MPE', 'EEE', 'CSE', 'CEE')
      AND code[4]::integer BETWEEN 1 AND 60
)
UPDATE student_roster r
SET student_id = t.new_student_id,
    updated_at = now()
FROM translated t
WHERE r.id = t.id;

WITH legacy AS (
    SELECT id, student_id,
           regexp_match(student_id, '^([A-Z]+)([1-4])([AB])([0-9]{3})$') AS code
    FROM users
    WHERE student_id IS NOT NULL
), translated AS (
    SELECT id, student_id AS old_student_id,
           CASE code[2]
               WHEN '1' THEN '24'
               WHEN '2' THEN '23'
               WHEN '3' THEN '22'
               WHEN '4' THEN '21'
           END
           || '00'
           || CASE code[1]
               WHEN 'MPE' THEN '11'
               WHEN 'EEE' THEN '21'
               WHEN 'CSE' THEN '41'
               WHEN 'CEE' THEN '51'
           END
           || CASE code[3] WHEN 'A' THEN '1' WHEN 'B' THEN '2' END
           || lpad((code[4]::integer)::text, 2, '0') AS new_student_id
    FROM legacy
    WHERE code IS NOT NULL
      AND code[1] IN ('MPE', 'EEE', 'CSE', 'CEE')
      AND code[4]::integer BETWEEN 1 AND 60
)
UPDATE users u
SET student_id = t.new_student_id,
    login_id = CASE WHEN u.login_id = t.old_student_id THEN t.new_student_id ELSE u.login_id END,
    updated_at = now()
FROM translated t
WHERE u.id = t.id;
