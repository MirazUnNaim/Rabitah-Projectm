-- Existing rooms remain section rooms. Department rooms use the reserved scope markers '*' and 0.
ALTER TABLE community_rooms
    ADD COLUMN room_type varchar(16) NOT NULL DEFAULT 'SECTION';

ALTER TABLE community_rooms
    ADD CONSTRAINT chk_community_room_type CHECK (room_type IN ('SECTION', 'DEPARTMENT'));

CREATE INDEX idx_community_rooms_access
    ON community_rooms(department_code, room_type, section_code, academic_year);
