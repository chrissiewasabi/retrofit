// Copyright 2013 Square, Inc.
package retrofit;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import retrofit.http.Body;
import retrofit.http.GET;
import retrofit.http.POST;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class RestAdapterTest {
  @Rule public final MockWebServerRule server = new MockWebServerRule();

  interface CallMethod {
    @GET("/") Call<String> disallowed();
    @POST("/") Call<String> disallowed(@Body String body);
    @GET("/") Call<ResponseBody> allowed();
    @POST("/") Call<ResponseBody> allowed(@Body RequestBody body);
  }
  interface FutureMethod {
    @GET("/") Future<String> method();
  }
  interface Extending extends CallMethod {
  }

  @SuppressWarnings("EqualsBetweenInconvertibleTypes") // We are explicitly testing this behavior.
  @Test public void objectMethodsStillWork() {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    CallMethod example = ra.create(CallMethod.class);

    assertThat(example.hashCode()).isNotZero();
    assertThat(example.equals(this)).isFalse();
    assertThat(example.toString()).isNotEmpty();
  }

  @Test public void interfaceWithExtendIsNotSupported() {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    try {
      ra.create(Extending.class);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Interface definitions must not extend other interfaces.");
    }
  }

  @Test public void callReturnTypeAdapterAddedByDefault() {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    CallMethod example = ra.create(CallMethod.class);
    assertThat(example.allowed()).isNotNull();
  }

  @Test public void callReturnTypeCustomAdapter() {
    final AtomicBoolean factoryCalled = new AtomicBoolean();
    final AtomicBoolean adapterCalled = new AtomicBoolean();
    class MyCallAdapterFactory implements CallAdapter.Factory {
      @Override public CallAdapter<?> get(final Type returnType) {
        factoryCalled.set(true);
        if (Utils.getRawType(returnType) != Call.class) {
          return null;
        }
        return new CallAdapter<Object>() {
          @Override public Type responseType() {
            return Utils.getSingleParameterUpperBound((ParameterizedType) returnType);
          }

          @Override public Object adapt(Call<Object> call) {
            adapterCalled.set(true);
            return call;
          }
        };
      }
    }

    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .addCallAdapterFactory(new MyCallAdapterFactory())
        .build();
    CallMethod example = ra.create(CallMethod.class);
    assertThat(example.allowed()).isNotNull();
    assertThat(factoryCalled.get()).isTrue();
    assertThat(adapterCalled.get()).isTrue();
  }

  interface StringService {
    @GET("/") String get();
  }

  @Test public void customReturnTypeAdapter() {
    class GreetingCallAdapterFactory implements CallAdapter.Factory {
      @Override public CallAdapter<?> get(Type returnType) {
        if (Utils.getRawType(returnType) != String.class) {
          return null;
        }
        return new CallAdapter<Object>() {
          @Override public Type responseType() {
            return Object.class;
          }

          @Override public String adapt(Call<Object> call) {
            return "Hi!";
          }
        };
      }
    }

    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .addCallAdapterFactory(new GreetingCallAdapterFactory())
        .build();
    StringService example = ra.create(StringService.class);
    assertThat(example.get()).isEqualTo("Hi!");
  }

  @Test public void customReturnTypeAdapterMissingThrows() {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    FutureMethod example = ra.create(FutureMethod.class);
    try {
      example.method();
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("FutureMethod.method: No registered call adapters were able to "
          + "handle return type java.util.concurrent.Future<java.lang.String>. "
          + "Checked: [Built-in CallAdapterFactory]");
    }
  }

  @Test public void defaultConverterThrowsSerializing() throws IOException {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    CallMethod example = ra.create(CallMethod.class);
    Call<String> call = example.disallowed("Hi!");
    try {
      call.execute();
      fail();
    } catch (UnsupportedOperationException e) {
      assertThat(e).hasMessage("No converter installed to handle serializing class java.lang.String");
    }
  }

  @Test public void defaultConverterThrowsDeserializing() throws IOException {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    CallMethod example = ra.create(CallMethod.class);

    server.enqueue(new MockResponse().setBody("Hi"));

    Call<String> call = example.disallowed();
    try {
      call.execute();
      fail();
    } catch (UnsupportedOperationException e) {
      assertThat(e).hasMessage("No converter installed to handle deserializing body to class java.lang.String");
    }
  }

  @Test public void requestBodyOutgoingAllowed() throws IOException {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    CallMethod example = ra.create(CallMethod.class);

    server.enqueue(new MockResponse().setBody("Hi"));

    Response<ResponseBody> response = example.allowed().execute();
    assertThat(response.body().string()).isEqualTo("Hi");
  }

  @Test public void responseBodyIncomingAllowed() throws IOException, InterruptedException {
    RestAdapter ra = new RestAdapter.Builder()
        .endpoint(server.getUrl("/").toString())
        .build();
    CallMethod example = ra.create(CallMethod.class);

    server.enqueue(new MockResponse().setBody("Hi"));

    RequestBody body = RequestBody.create(MediaType.parse("text/plain"), "Hey");
    Response<ResponseBody> response = example.allowed(body).execute();
    assertThat(response.body().string()).isEqualTo("Hi");

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("Hey");
  }
}
