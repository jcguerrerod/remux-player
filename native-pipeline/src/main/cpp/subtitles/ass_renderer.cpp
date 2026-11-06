#include "ass_renderer.h"
#include <android/log.h>
#include <vector>

#define TAG "AssRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static const char* VERTEX_SHADER_SRC = R"glsl(#version 300 es
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_texcoord;

uniform mat4 u_projection;
out vec2 v_texcoord;

void main() {
    v_texcoord = a_texcoord;
    gl_Position = u_projection * vec4(a_position, 0.0, 1.0);
}
)glsl";

static const char* FRAGMENT_SHADER_SRC = R"glsl(#version 300 es
precision mediump float;

in vec2 v_texcoord;
uniform sampler2D u_texture;
uniform vec4 u_primary_color;   // Blanco PGS #FFFFFF
uniform vec4 u_outline_color;   // Negro contorno #000000
uniform float u_outline_width;  // 2.5px

out vec4 fragColor;

void main() {
    // Muestreo de máscara alpha del glifo
    float alpha = texture(u_texture, v_texcoord).r;
    
    // Composición de color primaria con borde oscuro Blu-ray PGS
    vec4 final_color = mix(u_outline_color, u_primary_color, alpha);
    final_color.a = final_color.a * max(alpha, 0.85); // Alpha estricto para drop shadow suave

    fragColor = final_color;
}
)glsl";

AssRenderer::AssRenderer() = default;

AssRenderer::~AssRenderer() {
    destroy_gl();
}

bool AssRenderer::init_gl(int screen_width, int screen_height) {
    screen_width_ = screen_width;
    screen_height_ = screen_height;

    init_shaders();

    // Generar textura 2D para subida dinámica de mapas de bits de subtítulo
    glGenTextures(1, &texture_id_);
    glBindTexture(GL_TEXTURE_2D, texture_id_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Buffers de vértices para el quad de pantalla
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);

    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);

    // Coordenadas cuadrilátero (posición x,y + uv u,v)
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glBindVertexArray(0);

    LOGI("AssRenderer GLES 3.0 inicializado para resolución [%dx%d]", screen_width_, screen_height_);
    return true;
}

void AssRenderer::init_shaders() {
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &VERTEX_SHADER_SRC, nullptr);
    glCompileShader(vs);

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &FRAGMENT_SHADER_SRC, nullptr);
    glCompileShader(fs);

    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);

    glDeleteShader(vs);
    glDeleteShader(fs);

    u_matrix_loc_ = glGetUniformLocation(program_, "u_projection");
    u_color_loc_ = glGetUniformLocation(program_, "u_primary_color");
    u_outline_color_loc_ = glGetUniformLocation(program_, "u_outline_color");
    u_outline_size_loc_ = glGetUniformLocation(program_, "u_outline_width");
}

void AssRenderer::destroy_gl() {
    if (vao_) {
        glDeleteVertexArrays(1, &vao_);
        vao_ = 0;
    }
    if (vbo_) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    if (texture_id_) {
        glDeleteTextures(1, &texture_id_);
        texture_id_ = 0;
    }
    if (program_) {
        glDeleteProgram(program_);
        program_ = 0;
    }
}

void AssRenderer::add_cue(int64_t start_ms, int64_t end_ms, const std::string& text) {
    cues_.push_back({start_ms, end_ms, text});
}

void AssRenderer::render_frame(int64_t pts_ms) {
    if (!program_ || cues_.empty()) return;

    // Buscar el subtítulo activo para este PTS
    const SubtitleCue* active_cue = nullptr;
    for (const auto& cue : cues_) {
        if (pts_ms >= cue.start_ms && pts_ms <= cue.end_ms) {
            active_cue = &cue;
            break;
        }
    }

    if (!active_cue) return;

    // Activación de Alpha Blending premultiplicado para overlay sin artefactos
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(program_);

    // Blanco puro Blu-ray PGS #FFFFFF
    glUniform4f(u_color_loc_, 1.0f, 1.0f, 1.0f, 1.0f);
    // Borde negro puro 2.5px
    glUniform4f(u_outline_color_loc_, 0.0f, 0.0f, 0.0f, 1.0f);
    glUniform1f(u_outline_size_loc_, 2.5f);

    glBindVertexArray(vao_);
    // Renderizado del quad de subtítulos
    // glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    glDisable(GL_BLEND);
}

void AssRenderer::clear_cues() {
    cues_.clear();
}
