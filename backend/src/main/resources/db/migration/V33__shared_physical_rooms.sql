ALTER TABLE venue_resource ADD COLUMN classroom_resource_id BIGINT;
ALTER TABLE venue_resource ADD CONSTRAINT uq_venue_classroom UNIQUE(classroom_resource_id);
ALTER TABLE venue_resource ADD CONSTRAINT fk_venue_classroom FOREIGN KEY(classroom_resource_id) REFERENCES classroom_resource(id);
