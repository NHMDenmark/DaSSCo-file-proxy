package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.service.CacheFileService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.*;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

@Path("/files")
@Tag(name = "Asset Files", description = "Endpoints related to assets' files.")
@SecurityRequirement(name = "dassco-idp")
public class Files {
    private static final Logger logger = LoggerFactory.getLogger(Files.class);
    private final CacheFileService cacheFileService;
    @Context
    UriInfo uriInfo;

    @Inject
    public Files(CacheFileService cacheFileService) {
        this.cacheFileService = cacheFileService;
    }

    @GET
    @Path("/assets/{institution}/{collection}/{assetGuid}/{path: .+}")
    public Response getFile(
            @PathParam("institution") String institution
            , @PathParam("collection") String collection
            , @PathParam("assetGuid") String guid
            , @Context SecurityContext securityContext
    ) {
        final String path = uriInfo.getPathParameters().getFirst("path");
        logger.info("Getting file");
        if(securityContext == null) {
            return Response.status(401).build();
        }
        Optional<FileService.FileResult> file = cacheFileService.getFile(institution, collection, guid, path, UserMapper.from(securityContext));
        logger.info("got file");

        if (file.isPresent()) {
//            try {
            FileService.FileResult fileResult = file.get();
            StreamingOutput streamingOutput = output -> {
                fileResult.is().transferTo(output);
                output.flush();
            };
            return Response.status(200)
                    .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                    .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
//            }
//            finally {
//                try {
//                    file.get().is().close();
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            }
        } else {
            return Response.status(404).build();
        }
    }
}
