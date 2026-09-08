-- Facility index experiment; apply explicitly to public_service only.
-- Preconditions: 1,000,000 facilities and PRIMARY(id) only.
-- Not an application startup migration; do not rerun after application.
-- Applied separately in this order, with EXPLAIN/ANALYZE between statements.
-- InnoDB appends the primary key id to each secondary index internally.

-- B: region equality leaves id ordered; COUNT is covered.
CREATE INDEX idx_facility_region ON public_service.facility (region);

-- A: both equality columns fixed, followed by implicit id ordering.
CREATE INDEX idx_facility_region_type ON public_service.facility (region, type);

-- C: all three equality columns fixed, followed by implicit id ordering.
CREATE INDEX idx_facility_region_district_type ON public_service.facility (region, district, type);
