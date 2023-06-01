package dk.northtech.dasscofileproxy.configuration;

import jakarta.annotation.Priority;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Provider
@Priority(1)
public class ClientAbortInterceptor implements WriterInterceptor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientAbortInterceptor.class);

  @Override
  public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
    try {
      context.proceed();
    } catch (Throwable ClientAbortException) {
      var entity = context.getEntity();
      if (entity == null) {
        LOGGER.debug("Client aborted request for empty response");
      } else {
        LOGGER.debug("Client aborted request for {}", entity.getClass().getSimpleName());
      }
    }
  }
}
