#ifndef ASS_RENDERER_H
#define ASS_RENDERER_H

#include <GLES3/gl3.h>
#include <cstdint>
#include <string>
#include <vector>

struct SubtitleCue {
    int64_t start_ms;
    int64_t end_ms;
    std::string text;
};

class AssRenderer {
public:
    AssRenderer();
    ~AssRenderer();

    /**
     * Inicializa el motor GLES 3.0 y compila los shaders de composición sobre la superficie.
     */
    bool init_gl(int screen_width, int screen_height);
    void destroy_gl();

    /**
     * Inserta un subtítulo de texto (SRT/VTT) para estilarlo como PGS.
     */
    void add_cue(int64_t start_ms, int64_t end_ms, const std::string& text);

    /**
     * Renderiza el frame de subtítulo correspondiente al PTS actual.
     */
    void render_frame(int64_t pts_ms);

    void clear_cues();

private:
    void init_shaders();

    int screen_width_ = 3840;
    int screen_height_ = 2160;

    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint texture_id_ = 0;

    GLint u_matrix_loc_ = -1;
    GLint u_color_loc_ = -1;
    GLint u_outline_color_loc_ = -1;
    GLint u_outline_size_loc_ = -1;

    std::vector<SubtitleCue> cues_;
};

#endif // ASS_RENDERER_H
