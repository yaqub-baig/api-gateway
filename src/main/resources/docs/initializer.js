// Loaded as a file (not inline) so the page can use Content-Security-Policy script-src 'self'.
window.onload = function () {
  fetch('/_docs/swagger-config', { credentials: 'same-origin' })
    .then(function (r) { return r.json(); })
    .then(function (cfg) {
      window.ui = SwaggerUIBundle({
        urls: cfg.urls,
        'urls.primaryName': cfg.primaryName,
        dom_id: '#swagger-ui',
        deepLinking: true,
        presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
        plugins: [SwaggerUIBundle.plugins.DownloadUrl],
        layout: 'StandaloneLayout',
        // The default validator calls validator.swagger.io; nothing leaves this host.
        validatorUrl: null,
        persistAuthorization: true
      });
    });
};
