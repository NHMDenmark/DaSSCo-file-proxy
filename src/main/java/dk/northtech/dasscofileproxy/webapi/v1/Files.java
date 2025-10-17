package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoUnauthorizedException;
import dk.northtech.dasscofileproxy.service.CacheFileService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Optional;


@Path("/files")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class Files {
    private static final Logger logger = LoggerFactory.getLogger(Files.class);
    private final CacheFileService cacheFileService;
    private FileService fileService;
    private final AssetServiceProperties assetServiceProperties;
    @Context
    UriInfo uriInfo;

    @Inject
    public Files(CacheFileService cacheFileService, FileService fileService, AssetServiceProperties assetServiceProperties) {
        this.cacheFileService = cacheFileService;
        this.fileService = fileService;
        this.assetServiceProperties = assetServiceProperties;
    }


    @GET
    @Operation(summary = "Get File From ERDA", description = """
    Gets a file from ERDA on the give path. If 'no-cache' is true, then the file won't be saved in the cache and will be streamed instead. 'no-cache' is false by default.
    """)
    @Path("/assets/{institution}/{collection}/{assetGuid}/{path: .+}")
    public Response getFile(
            @PathParam("institution") String institution
            , @PathParam("collection") String collection
            , @PathParam("assetGuid") String guid
            , @Context SecurityContext securityContext
            , @QueryParam("no-cache") @DefaultValue("false") boolean noCache
    ) {
        final String path = uriInfo.getPathParameters().getFirst("path");
        logger.info("Getting file from collection, {}, on path: {}", collection, path);
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous") : UserMapper.from(securityContext);
        try{
            if (!noCache){
                Optional<FileService.FileResult> file = cacheFileService.getFile(institution, collection, guid, path, user);
                logger.info("got file");

                if (file.isPresent()) {
                    FileService.FileResult fileResult = file.get();
                    StreamingOutput streamingOutput = output -> {
                        fileResult.is().transferTo(output);
                        output.flush();
                    };

                    return Response.status(200)
                            .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                            .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
                } else {
                    return Response.status(404).build();
                }
            } else {
                return cacheFileService.streamFile(institution, collection, guid, path, user, false);
            }
        }
        catch (DasscoUnauthorizedException e) {
            String redirectUrl = assetServiceProperties.rootUrl() + "/detailed-view/" + guid;
            return Response.status(Response.Status.TEMPORARY_REDIRECT).location(URI.create(redirectUrl)).build();
        }
    }

    @GET
    @Path("/assets/{institutionName}/{collectionName}/{assetGuid}/thumbnail")
    public Response getFileFromGuid(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context SecurityContext securityContext, @QueryParam("no-cache") @DefaultValue("false") boolean noCache) {
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous") : UserMapper.from(securityContext);
        Optional<DasscoFile> dasscoFile = this.fileService.getDasscoFileThumbnailForGuid(assetGuid);
        if(dasscoFile.isPresent()) {
            String path = dasscoFile.get().path();
            if(!(path.toLowerCase().endsWith(".jpeg") || path.toLowerCase().endsWith(".jpg") || path.toLowerCase().endsWith(".png"))) {
                return Response.status(404).build();
            }
            try{
                String fileName = List.of(path.split("/")).getLast();

                if (!noCache){
                    Optional<FileService.FileResult> file = cacheFileService.getFile(institutionName, collectionName, assetGuid, fileName, user);
                    if (file.isPresent()) {
                        FileService.FileResult fileResult = file.get();
                        StreamingOutput streamingOutput = output -> {
                            fileResult.is().transferTo(output);
                            output.flush();
                        };

                        return Response.status(200)
                                .header("Content-Disposition", "inline; attachment; filename=" + fileResult.filename())
                                .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
                    } else {
                        return Response.status(404).build();
                    }
                }
                else{
                    return cacheFileService.streamFile(institutionName, collectionName, assetGuid, fileName, user, true);
                }
            }
            catch (Exception e) {
                logger.error(e.toString());
            }
        }

        return Response.status(404).build();
    }

    @GET
    @Path("/assets/extern/{institutionName}/{collectionName}/{assetGuid}")
    public Response getExternFileFromGuid(@PathParam("institutionName") String institutionName, @PathParam("collectionName") String collectionName, @PathParam("assetGuid") String assetGuid, @Context SecurityContext securityContext) {
        User user = securityContext.getUserPrincipal() == null ? new User("anonymous") : UserMapper.from(securityContext);
        Optional<DasscoFile> dasscoFile = this.fileService.getDasscoFileForGuid(assetGuid);
        if (dasscoFile.isPresent()) {
            String path = dasscoFile.get().path();
            try {
                String fileName = List.of(path.split("/")).getLast();
                return cacheFileService.streamFile(institutionName, collectionName, assetGuid, fileName, user, true);
            } catch (Exception e) {
                logger.error(e.toString());
            }
        }

        return Response.status(404).build();
    }
}
