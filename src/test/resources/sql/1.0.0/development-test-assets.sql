-- --Institutions
-- INSERT INTO institution(institution_name)
-- VALUES ('institution_1'),
--        ('institution_2');
--
--
-- --Collections
--
-- INSERT INTO collection(collection_name, institution_name)
-- VALUES ('i1_c1','institution_1')
--      , ('i1_c2','institution_1')
--      , ('i2_c1','institution_2');
--
-- -- Workstations
-- INSERT INTO workstation(workstation_name, institution_name, workstation_status)
-- VALUES ('i1_w1', 'institution_1', 'IN_SERVICE')
--      , ('i1_w2', 'institution_1', 'IN_SERVICE')
--      , ('i1_w3', 'institution_1', 'OUT_OF_SERVICE')
--      , ('i2_w1', 'institution_2', 'IN_SERVICE');
--
-- -- Pipeline
-- INSERT INTO pipeline( pipeline_name, institution_name)
-- VALUES ('i1_p1', 'institution_1')
--      , ('i1_p2', 'institution_1')
--      , ('i2_p1', 'institution_2')
--      , ('i2_p1', 'institution_2');
WITH c_id AS (SELECT collection_id FROM collection WHERE collection_name = 'i1_c1')
INSERT INTO specimen(collection_id, specimen_pid, barcode, preparation_type)
SELECT c_id.collection_id, 'test_specimen_1_pid', 'test_specimen_1_barcode', 'slide'
FROM c_id;

WITH c_id AS (SELECT collection_id FROM collection WHERE collection_name = 'i1_c1')
INSERT INTO specimen(collection_id, specimen_pid, barcode, preparation_type)
SELECT c_id.collection_id, 'test_specimen_2_pid', 'test_specimen_2_barcode', 'pinning'
FROM c_id;

INSERT INTO public.asset(
                          asset_guid
                        , asset_pid
                        , asset_locked
                        , subject
                        , collection_id
                        , digitiser_id
                        , file_formats
                        , payload_type
                        , status
                        , tags
                        , workstation_id
                        , internal_status
                        , make_public
                        , metadata_source
                        , push_to_specify
                        , metadata_version
                        , camera_setting_control
                        , date_asset_taken
                        , date_asset_finalised
                        , initial_metadata_recorded_by
                        , date_metadata_ingested
                        , legality_id
) VALUES (
           'deleteShare_1'
         , 'deleteShare_1_pid'
         , false
         , 'folder'
         , (SELECT collection_id FROM collection WHERE collection_name = 'i1_c1')
         , null
         , null
         , 'conventional'
         , 'WORKING_COPY'
         , null
         , (SELECT workstation_id FROM workstation WHERE workstation_name = 'i1_w1')
         , 'METADATA_RECEIVED'
         , true
         , 'I made it all up'
         , true
         , 'one point uh-oh'
         , 'Mom get the camera!'
         , now()
         , now()
         , 'bazviola'
         , now()
         , null
         );

