package dk.northtech.dasscofileproxy.webapi.v1;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.domain.AssetFull;
import dk.northtech.dasscofileproxy.service.AssetService;
import dk.northtech.dasscofileproxy.service.ERDAClient;
import dk.northtech.dasscofileproxy.service.SFTPService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.tika.Tika;
import org.json.JSONArray;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Hidden
@Component
@Path("/v1/sftp")
// TODO: As with FTPS, these endpoints are not available in Postman nor there is any mention of them in the documentation.
@Tag(name = "Secure File Transfer Protocol", description = "Endpoints related to the secure file transfer")
@SecurityRequirement(name = "dassco-idp")
public class SFTPApi {

    private final SFTPService sftpService;

    private final SFTPConfig sftpConfig;

    private final AssetService assetService;

    @Inject
    public SFTPApi(SFTPService sftpService, AssetService assetService, SFTPConfig sftpConfig) {
        this.sftpService = sftpService;
        this.assetService = assetService;
        this.sftpConfig = sftpConfig;
    }

    @Hidden
    @GET
    @Path("/hello")
    @Produces(MediaType.APPLICATION_JSON)
    public String hello() {
        return "Hello!";
    }


    @GET
    @Path("/institutions/{institution}/collections/{collection}/assets/{guid}")
    @Operation(summary = "Get File by Institution, Collection and GUID", description = "")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> listItems(@PathParam("institution") String institution
            , @PathParam("collection") String collection
            , @PathParam("guid") String guid) throws JSchException, SftpException {
        return new ERDAClient(sftpConfig).listFiles("/" + institution + "/" + collection + "/" + guid);
    }

    @GET
    @Path("/assets/{guid}/files/{file}")
    @Operation(summary = "Get File by GUID", description = "")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response get(@PathParam("guid") String guid, @PathParam("file") String file, @Context HttpHeaders headers) throws IOException, SftpException {
        // Read the asset from the asset service to obtain restricted status and remaining path for the asset
        AssetFull assetFull = assetService.getFullAsset(guid);
        // If there is no asset for the asset_guid in asset service throw exception, as the client needs institution and collection to locate file
        if (assetFull == null)
            return Response.status(Response.Status.NOT_FOUND).entity("Cannot find asset in asset service.").build();

        // Determine whether the user should be allowed access to the file
        AtomicBoolean userHasAccess = new AtomicBoolean(false);
        if (!assetFull.restricted_access.isEmpty()) {
            if (headers == null || headers.getHeaderString(HttpHeaders.AUTHORIZATION) == null) return Response.status(Response.Status.UNAUTHORIZED).entity("The requested asset has restricted access and the request does not include valid authorization header.").build();

            String authHeader = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
            String[] parts = authHeader.split(" ");
            if (parts.length!=2) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Authorization header must comply with basic auth headers, i.e. the headers content should look like \"Bearer {token}\".").build();
            }
            DecodedJWT jwt = JWT.decode(parts[1]);

            String rolesStr = jwt.getClaims().get("realm_access").toString();
            String toConvert = rolesStr.substring(rolesStr.indexOf("["));
            toConvert = toConvert.substring(0, toConvert.indexOf("]")+1);
            JSONArray rolesArray = new JSONArray(toConvert);

            // Convert the JSONArray to a List of Strings
            List<String> roles = new ArrayList<>();
            for (int i = 0; i < rolesArray.length(); i++) {
                roles.add(rolesArray.getString(i));
            }

            assetFull.restricted_access.forEach(role -> {
                if (roles.contains(role.name())) {
                    userHasAccess.set(true);
                }
            });


        } else {
            userHasAccess.set(true);
        }
        if (!userHasAccess.get()) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("The requested asset has restricted access and the user does not have any of the required roles.").build();
        }

        // Build full path for requested file
        String localPath = sftpService.getLocalFolder(assetFull.institution, assetFull.collection, assetFull.asset_guid);
        String remotePath = sftpService.getRemotePath(assetFull.institution, assetFull.collection, assetFull.asset_guid);



        // Download the file from the SFTP server
        InputStream fileStream = new ERDAClient(sftpConfig).getFileInputStream(remotePath + "/" + file);
        if (fileStream == null) {
            // File not found on the FTP server
            return Response.status(404).entity("The requested resource could not be found.").build();
        }

        // Asynchronously start caching file
        CompletableFuture.supplyAsync(() -> {
            sftpService.cacheFile(remotePath + "/" + file, localPath + "/" + file);
            return null;
        });

        // Return the file as a response
        return Response.ok(fileStream).header("Content-Disposition", "attachment; filename=\"" + file + "\"")
                .header("Content-Type", getContentTypeFromFileName(file)).build();
    }

    public String getContentTypeFromFileName(String fileName) {
        Tika tika = new Tika();
        return tika.detect(fileName);
    }

}
