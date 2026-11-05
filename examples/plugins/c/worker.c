#include "sdk.h"
#include <locale.h>

int main(void) {
    setlocale(LC_NUMERIC, "C");
    char *line;
    while ((line = psd_read_line()) != NULL) {
        PsdToken *tokens = (PsdToken *)calloc(262144, sizeof(PsdToken));
        if (!tokens) { free(line); return 1; }
        PsdJson request = { line, 0, 0, 262144, tokens };
        if (psd_value(&request, 0) < 0) return 2;
        int id = psd_field(&request, 0, "id");
        int config = psd_field(&request, 0, "config");
        int factor = psd_field(&request, config, "factor");
        int meshes = psd_field(&request, 0, "meshes");
        if (id < 0 || factor < 0 || meshes < 0) return 3;
        printf("{\"protocol\":1,\"id\":"); psd_print(&request, id);
        printf(",\"commands\":[");
        int comma = 0;
        for (int mesh = meshes + 1; mesh < tokens[meshes].next; mesh = tokens[mesh].next) {
            int meshId = psd_field(&request, mesh, "id");
            int opacity = psd_field(&request, mesh, "opacity");
            if (meshId < 0 || opacity < 0) return 4;
            if (comma++) putchar(',');
            printf("{\"op\":\"mesh_opacity\",\"id\":"); psd_print(&request, meshId);
            printf(",\"value\":%.9g}", psd_number(&request, opacity) * psd_number(&request, factor));
        }
        printf("]}\n"); fflush(stdout);
        free(tokens); free(line);
    }
    return 0;
}
