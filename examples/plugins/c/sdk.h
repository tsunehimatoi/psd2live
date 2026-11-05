#ifndef PSD2LIVE_PLUGIN_SDK_H
#define PSD2LIVE_PLUGIN_SDK_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

/* Tokens retain JSON spelling, including escaped strings. No platform JSON library is required. */
typedef struct { int start, end, next; char kind; } PsdToken;
typedef struct { const char *json; int position, count, capacity; PsdToken *tokens; } PsdJson;
static void psd_space(PsdJson *p) { while (isspace((unsigned char)p->json[p->position])) p->position++; }
static int psd_value(PsdJson *p, int depth) {
    if (depth > 128 || p->count == p->capacity) return -1;
    psd_space(p);
    int index = p->count++;
    PsdToken *token = &p->tokens[index];
    token->start = p->position; token->kind = p->json[p->position];
    if (token->kind == '{' || token->kind == '[') {
        char close = token->kind == '{' ? '}' : ']';
        p->position++; psd_space(p);
        while (p->json[p->position] != close) {
            if (!p->json[p->position] || psd_value(p, depth + 1) < 0) return -1;
            psd_space(p);
            if (p->json[p->position] == ':' || p->json[p->position] == ',') { p->position++; psd_space(p); }
        }
        p->position++;
    } else if (token->kind == '"') {
        p->position++;
        while (p->json[p->position] && p->json[p->position] != '"') {
            if (p->json[p->position] == '\\') {
                if (!p->json[++p->position]) return -1;
            }
            p->position++;
        }
        if (!p->json[p->position]) return -1;
        p->position++;
    } else {
        while (p->json[p->position] && !strchr(",]} \r\n\t", p->json[p->position])) p->position++;
        if (p->position == token->start) return -1;
    }
    token->end = p->position; token->next = p->count;
    return index;
}
static int psd_field(const PsdJson *p, int object, const char *key) {
    if (object < 0 || p->tokens[object].kind != '{') return -1;
    for (int i = object + 1; i < p->tokens[object].next;) {
        int value = i + 1;
        if (value >= p->tokens[object].next) return -1;
        if (p->tokens[i].end - p->tokens[i].start == (int)strlen(key) + 2 &&
            strncmp(p->json + p->tokens[i].start + 1, key, strlen(key)) == 0) return value;
        i = p->tokens[value].next;
    }
    return -1;
}
static void psd_print(const PsdJson *p, int token) {
    fwrite(p->json + p->tokens[token].start, 1, (size_t)(p->tokens[token].end - p->tokens[token].start), stdout);
}
static double psd_number(const PsdJson *p, int token) { return strtod(p->json + p->tokens[token].start, NULL); }
static char *psd_read_line(void) {
    size_t count = 0, capacity = 4096;
    char *line = (char *)malloc(capacity);
    if (!line) return NULL;
    int c;
    while ((c = getchar()) != EOF && c != '\n') {
        if (count + 1 >= capacity) {
            if (capacity >= 16 * 1024 * 1024) { free(line); return NULL; }
            capacity *= 2;
            char *next = (char *)realloc(line, capacity);
            if (!next) { free(line); return NULL; }
            line = next;
        }
        line[count++] = (char)c;
    }
    if (!count && c == EOF) { free(line); return NULL; }
    line[count] = 0; return line;
}
#endif
