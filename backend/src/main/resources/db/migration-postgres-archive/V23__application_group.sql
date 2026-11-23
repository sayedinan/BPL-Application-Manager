-- V23 - application groups.
--
-- A group is just a label ("Onboarding", "Face match") that the Dashboard uses
-- to show applications in sections, each with its own "n/m healthy" count.
-- NULL means no group; those applications are listed under "Other".
--
-- A group is not a table of its own: it exists while at least one application
-- carries its name, and typing a new name starts a new one. It is set from the
-- Applications page by a Sys.Admin, separately from editing the application, so
-- it can be changed while the application is running (editing is locked then).

ALTER TABLE applications
    ADD COLUMN group_name VARCHAR(60);
