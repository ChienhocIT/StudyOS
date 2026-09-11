-- Coordinate creation with container cleanup using workspace -> notebook lock order.
-- A creator that waited behind deletion must observe the parent's committed status.
CREATE FUNCTION studyos_guard_notebook_creation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text;
BEGIN
    SELECT status INTO parent_status FROM workspaces WHERE id=NEW.workspace_id FOR SHARE;
    IF parent_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Notebook workspace is unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER notebook_creation_scope_guard BEFORE INSERT ON notebooks
FOR EACH ROW EXECUTE FUNCTION studyos_guard_notebook_creation();

CREATE FUNCTION studyos_guard_source_creation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text;
BEGIN
    SELECT status INTO parent_status FROM workspaces WHERE id=NEW.workspace_id FOR SHARE;
    IF parent_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Source workspace is unavailable' USING ERRCODE='23514';
    END IF;
    SELECT status INTO parent_status FROM notebooks
      WHERE id=NEW.notebook_id AND workspace_id=NEW.workspace_id FOR SHARE;
    IF parent_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Source notebook is unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER source_creation_scope_guard BEFORE INSERT ON sources
FOR EACH ROW EXECUTE FUNCTION studyos_guard_source_creation();
