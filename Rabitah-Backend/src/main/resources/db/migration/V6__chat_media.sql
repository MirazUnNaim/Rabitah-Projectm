ALTER TABLE private_messages ADD COLUMN storage_key varchar(500);
ALTER TABLE private_messages ADD COLUMN original_name varchar(255);
ALTER TABLE private_messages ADD COLUMN content_type varchar(100);
ALTER TABLE private_messages ADD COLUMN size_bytes bigint;

ALTER TABLE community_messages ADD COLUMN storage_key varchar(500);
ALTER TABLE community_messages ADD COLUMN original_name varchar(255);
ALTER TABLE community_messages ADD COLUMN content_type varchar(100);
ALTER TABLE community_messages ADD COLUMN size_bytes bigint;
