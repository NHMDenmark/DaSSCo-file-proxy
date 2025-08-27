--Institutions
INSERT INTO institution(institution_name)
VALUES ('institution_1'),
       ('institution_2');


--Collections

INSERT INTO collection(collection_name, institution_name)
VALUES ('i1_c1','institution_1')
     , ('i1_c2','institution_1')
     , ('i2_c1','institution_2');

-- Workstations
INSERT INTO workstation(workstation_name, institution_name, workstation_status)
VALUES ('i1_w1', 'institution_1', 'IN_SERVICE')
     , ('i1_w2', 'institution_1', 'IN_SERVICE')
     , ('i1_w3', 'institution_1', 'OUT_OF_SERVICE')
     , ('i2_w1', 'institution_2', 'IN_SERVICE');

-- Pipeline
INSERT INTO pipeline( pipeline_name, institution_name)
VALUES ('i1_p1', 'institution_1')
     , ('i1_p2', 'institution_1')
     , ('i2_p1', 'institution_2')
     , ('i2_p1', 'institution_2');
