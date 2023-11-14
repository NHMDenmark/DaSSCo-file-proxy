package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AssetFull {
    // @Schema(description = "Pid", example = "asdf-1234-3333-1000")
    public String asset_pid;
    // @Schema(description = "Unique key for the asset?", example = "ti-a01-202305241657")
    public String asset_guid;
    // @Schema(description = "The status of the asset", example = "BEING_PROCESSED")
    public AssetStatus status;
    // @Schema(description = "Basically a multispecimen is a single image (or other type of media) that actually contains multiple specimens in it", example = "false")
    public boolean multi_specimen;
    // @Schema(description = "The barcodes of associated specimens", example = "'[\"ti-sp-00012\"']")
    public List<String> specimen_barcodes = new ArrayList<>(); //TODO fix new structure
    // @Schema(description = "A short description of funding source used to create the asset", example = "Hundredetusindvis af dollars")
    public String funding;
    // @Schema(description = "We will need to distinguish between image of a folder, device target, specimen, label etc)", example = "folder")
    public String subject;
    // @Schema(description = "image, ct scan, surface scan, document", example = "ct scan")
    public String payload_type;
    // @Schema(description = "File format enum, can contain multiple formats")
    public List<FileFormat> file_formats = new ArrayList<>();
    // @Schema(description = "Flags if it is possible to edit / delete the media of this asset", example = "false")
    public boolean asset_locked;
    public List<Role> restricted_access = new ArrayList<>();

    // @Schema(description = "A dictionary of dynamic properties")
    public Map<String, String> tags = new HashMap<>();
    // @Schema(description = "Marking if this asset has been audited atleast once", example = "true")
    public boolean audited;

    // @Schema(description = "Date the asset metadata was uploaded", example = "2023-05-24T00:00:00.000Z")
    public Instant created_date;
    // @Schema(description = "Date the asset metadata was last updated", example = "2023-05-24T00:00:00.000Z")
    public Instant last_updated_date;
    // @Schema(description = "Date the asset was taken", example = "2023-05-24T00:00:00.000Z")
    public Instant asset_taken_date;
    // @Schema(description = "Date the asset was marked as deleted in the metadata", example = "2023-05-24T00:00:00.000Z")
    public Instant asset_deleted_date;

    //References
    // @Schema(description = "The institution", example = "NNAD")
    public String institution;
    //   @Schema(description = "The institution", example = "NNAD")
    // @Schema(description = "GUID of the parent asset", example = "ti-a02-202305241657")
    public String parent_guid;
    // @Schema(description = "Name of the collection the asset belongs to", example = "test-collection")
    public String collection;
    // @Schema(description = "The location on the storage where asset media can be uploaded", example = "/test-institution/test-collection/ti-a02-202305241657")
    public String asset_location;
    // @Schema(description = "An internal status field used to track the status of the upload of related media", example = "COMPLETED")
    public InternalStatus internal_status;
    // @Schema(description = "Date the asset was pushed to specify", example = "2023-05-24T00:00:00.000Z")
    public Instant pushed_to_specify_date;
    // @Schema(description = "Username of the person that digitised the asset,", example = "THBO")
    public String digitizer;
    // @Schema(description = "The pipeline that created or updtated the asset", example = "ti-p1")
    public String pipeline;
    // @Schema(description = "The name of the workstation that created or updated the asset", example = "ti-ws1")
    public String workstation;
    // @Schema(description = "Username of the person that updated the asset", example = "THBO")
    public String updateUser;
}
