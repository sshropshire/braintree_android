package com.braintreepayments.api;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.braintreepayments.api.GraphQLConstants.Features;

import org.json.JSONException;

import java.lang.ref.WeakReference;

import static com.braintreepayments.api.PaymentMethodNonce.parsePaymentMethodNonces;

class TokenizationClient {

    static final String PAYMENT_METHOD_ENDPOINT = "payment_methods";

    private final WeakReference<BraintreeClient> braintreeClientRef;

    TokenizationClient(BraintreeClient braintreeClient) {
        this(new WeakReference<>(braintreeClient));
    }

    @VisibleForTesting
    TokenizationClient(WeakReference<BraintreeClient> braintreeClientRef) {
        this.braintreeClientRef = braintreeClientRef;
    }

    /**
     * Create a {@link PaymentMethodNonce} in the Braintree Gateway.
     * <p>
     * On completion, returns the {@link PaymentMethodNonce} to {@link PaymentMethodNonceCallback}.
     * <p>
     * If creation fails validation, {@link BraintreeErrorListener#onError(Exception)}
     * will be called with the resulting {@link ErrorWithResponse}.
     * <p>
     * If an error not due to validation (server error, network issue, etc.) occurs, {@link
     * BraintreeErrorListener#onError(Exception)} (Throwable)}
     * will be called with the {@link Exception} that occurred.
     *
     * @param paymentMethodBuilder {@link PaymentMethodBuilder} for the {@link PaymentMethodNonce}
     *        to be created.
     */
    public <T> void tokenize(final PaymentMethodBuilder<T> paymentMethodBuilder, final PaymentMethodNonceCallback callback) {
        final BraintreeClient braintreeClient = braintreeClientRef.get();
        if (braintreeClient == null) {
            return;
        }

        paymentMethodBuilder.setSessionId(braintreeClient.getSessionId());
        braintreeClient.getConfiguration(new ConfigurationCallback() {
            @Override
            public void onResult(@Nullable Configuration configuration, @Nullable Exception error) {
                if (configuration != null) {
                    if (paymentMethodBuilder instanceof CardBuilder &&
                            configuration.getGraphQL().isFeatureEnabled(Features.TOKENIZE_CREDIT_CARDS)) {
                        tokenizeGraphQL(braintreeClient, (CardBuilder) paymentMethodBuilder, callback);
                    } else {
                        tokenizeRest(braintreeClient, paymentMethodBuilder, callback);
                    }
                } else {
                    callback.failure(error);
                }
            }
        });
    }

    private static void tokenizeGraphQL(final BraintreeClient braintreeClient, final CardBuilder cardBuilder, final PaymentMethodNonceCallback callback) {
        braintreeClient.sendAnalyticsEvent("card.graphql.tokenization.started");
        final String payload;
        try {
            payload = cardBuilder.buildGraphQL(braintreeClient.getAuthorization());
        } catch (BraintreeException e) {
            callback.failure(e);
            return;
        }

        braintreeClient.sendGraphQLPOST(payload, new HttpResponseCallback() {
            @Override
            public void success(String responseBody) {
                try {
                    callback.success(parsePaymentMethodNonces(responseBody, cardBuilder.getResponsePaymentMethodType()));
                    braintreeClient.sendAnalyticsEvent("card.graphql.tokenization.success");
                } catch (JSONException e) {
                    callback.failure(e);
                }
            }

            @Override
            public void failure(Exception exception) {
                braintreeClient.sendAnalyticsEvent("card.graphql.tokenization.failure");
                callback.failure(exception);
            }
        });
    }

    private static <T> void tokenizeRest(final BraintreeClient braintreeClient, final PaymentMethodBuilder<T> paymentMethodBuilder, final PaymentMethodNonceCallback callback) {
        String url = TokenizationClient.versionedPath(
                TokenizationClient.PAYMENT_METHOD_ENDPOINT + "/" + paymentMethodBuilder.getApiPath());

        braintreeClient.sendPOST(url, paymentMethodBuilder.build(), new HttpResponseCallback() {

            @Override
            public void success(String responseBody) {
                try {
                    callback.success(parsePaymentMethodNonces(responseBody,
                            paymentMethodBuilder.getResponsePaymentMethodType()));
                } catch (JSONException e) {
                    callback.failure(e);
                }
            }

            @Override
            public void failure(Exception exception) {
                callback.failure(exception);
            }
        });
    }

    static String versionedPath(String path) {
        return "/v1/" + path;
    }
}